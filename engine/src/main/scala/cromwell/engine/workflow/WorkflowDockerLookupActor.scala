package cromwell.engine.workflow

import akka.actor.{ActorRef, LoggingFSM, Props}
import cromwell.core.{Dispatcher, WorkflowId}
import cromwell.database.sql.tables.DockerHashStoreEntry
import cromwell.docker.DockerHashActor.{DockerHashFailureResponse, DockerHashSuccessResponse}
import cromwell.docker.{DockerClientHelper, DockerHashRequest, DockerHashResult, DockerImageIdentifier}
import cromwell.engine.workflow.WorkflowActor.{RestartExistingWorkflow, StartMode}
import cromwell.engine.workflow.WorkflowDockerLookupActor._
import cromwell.services.SingletonServicesStore
import lenthall.util.TryUtil

import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

/**
  * Ensures docker hashes consistency throughout a workflow.
  * 
  * Caches successful docker hash lookups and serve them to subsequent identical requests.
  * Persists those hashes in the database to be resilient to server restarts.
  * 
  * Failure modes:
  * 1) Fail to load hashes from DB upon restart
  * 2) Fail to parse hashes from the DB upon restart
  * 3) Fail to write hash result to the DB
  * 4) Fail to lookup docker hash
  * 
  * Behavior:
  * 1-2) Return lookup failures for all requests
  * 3-4) Return lookup failure for current request and all subsequent requests for the same tag
  */

class WorkflowDockerLookupActor(workflowId: WorkflowId, val dockerHashingActor: ActorRef, startMode: StartMode)
  extends LoggingFSM[WorkflowDockerLookupActorState, WorkflowDockerLookupActorData] with DockerClientHelper with SingletonServicesStore {

  implicit val ec = context.system.dispatchers.lookup(Dispatcher.EngineDispatcher)

  // Amount of time after which the docker request should be considered lost and sent again
  override protected def backpressureTimeout: FiniteDuration = 10 seconds
  // Amount of time to wait when we get a Backpressure response before sending the request again
  override protected def backpressureRandomizerFactor: Double = 0.5D

  private val restart = startMode == RestartExistingWorkflow

  context.become(dockerReceive orElse receive)

  if (restart) {
    startWith(LoadingCache, WorkflowDockerLookupActorData.emptyLoadingCacheData)
  } else {
    startWith(Running, WorkflowDockerLookupActorData.emptyRunningData)
  }

  override def preStart(): Unit = {
    if (restart) {
      databaseInterface.queryDockerHashStoreEntries(workflowId.toString) onComplete {
        case Success(dockerHashEntries) =>
          val dockerMappings = dockerHashEntries map { entry => entry.dockerTag -> entry.dockerHash } toMap

          self ! DockerHashStoreLoadingSuccess(dockerMappings)
        case Failure(ex) => self ! DockerHashStoreLoadingFailure(ex)
      }
    }
    super.preStart()
  }

  // Waiting for a response from the database with the hash mapping for this workflow
  when(LoadingCache) {
    case Event(request: DockerHashRequest, data) =>
      stay using data.enqueue(request, sender())
    case Event(DockerHashStoreLoadingSuccess(dockerHashEntries), data: LoadingCacheData) =>
      loadCache(dockerHashEntries, data)
    case Event(DockerHashStoreLoadingFailure(reason), _) =>
      log.error(reason, "Failed to load docker tag -> hash mappings from DB")
      goto(Failed)
  }

  // The normal operational mode
  when(Running) {
    case Event(request: DockerHashRequest, data: RunningData) if data.awaitingHashes.contains(request) =>
      // A request for the hash for this tag has already been made to the hashing actor.  Don't request the hash again,
      // just add this sender to the list of replyTos when the hash arrives.
      stay using data.enqueue(request, sender())
    case Event(request: DockerHashRequest, data: RunningData) =>
      handleRunningRequest(request, data)
    case Event(dockerResponse: DockerHashSuccessResponse, data: RunningData) =>
      handleLookupSuccess(dockerResponse, data)
    case Event(dockerResponse: DockerHashFailureResponse, data: RunningData) =>
      handleLookupFailure(dockerResponse, data)
    case Event(DockerHashStoreSuccess(response), data: RunningData) =>
      handleStoreSuccess(response, data)
    case Event(DockerHashStoreFailure(request, reason), data: RunningData) =>
      handleStoreFailure(request, reason, data)
  }

  // In FailMode we reject all requests
  when(Failed) {
    case Event(request: DockerHashRequest, _) =>
      sender() ! WorkflowDockerLookupFailure(FailedException, request)
      stay()
  }

  whenUnhandled {
    case Event(DockerHashActorTimeout(message), data) =>
      val reason = new Exception(s"Timeout looking up docker hash: $message")
      // This is just catastrophic, we have no way of knowing the offending request so fail everything.
      failEverything(reason, data)
      stay()
  }

  onTransition {
    // When transitioning to Failed, fail all enqueued requests
    case _ -> Failed =>
      failEverything(FailedException, stateData)
  }

  /**
    * Load mappings from the database into the state data, reply to queued requests which have mappings, and initiate
    * hash lookups for requests which don't have mappings.
    */
  private def loadCache(hashEntries: Map[String, String], data: LoadingCacheData) = {
    val dockerMappingsTry = hashEntries map {
      case (dockerTag, dockerHash) => DockerImageIdentifier.fromString(dockerTag) -> Try(DockerHashResult(dockerHash))
    }

    TryUtil.sequenceKeyValues(dockerMappingsTry) match {
      case Success(dockerMappings) =>
        // Figure out which of the queued requests already have established mappings.
        val (hasMapping, doesNotHaveMapping) = data.queuedRequests.partition { case (request, _) => dockerMappings.contains(request.dockerImageID) }

        // The requests which have mappings receive success responses.
        hasMapping foreach { case (request, replyTos) =>
          val result = dockerMappings(request.dockerImageID)
          replyTos foreach { _ ! DockerHashSuccessResponse(result, request)}
        }

        // The requests without mappings need to be looked up.
        doesNotHaveMapping.keys foreach { sendDockerCommand(_) }

        // Update state data accordingly.
        val runningData = RunningData(awaitingHashes = doesNotHaveMapping, mappings = dockerMappings)
        goto(Running) using runningData

      case Failure(reason) =>
        log.error(reason, "Failed to load docker tag -> hash mappings from DB")
        goto(Failed)
    }
  }

  def handleRunningRequest(request: DockerHashRequest, data: RunningData) = {
    sendDockerCommand(request)
    val replyTo = sender()
    val updatedData = data.copy(awaitingHashes = data.awaitingHashes + (request -> List(replyTo)))
    stay using updatedData
  }

  def handleStoreFailure(dockerHashRequest: DockerHashRequest, reason: Throwable, data: RunningData) = {
    val reason = new Exception(s"Timeout looking up docker hash for ${dockerHashRequest.dockerImageID.fullName}")
    val response = WorkflowDockerLookupFailure(reason, dockerHashRequest)
    data.awaitingHashes(dockerHashRequest) foreach { _ ! response }

    val updatedData = data.copy(awaitingHashes = data.awaitingHashes - dockerHashRequest)
    stay() using updatedData
  }


  def handleStoreSuccess(response: DockerHashSuccessResponse, data: RunningData) = {
    // Add the new label to hash mapping to the current set of mappings.
    val request = response.request
    val replyTos = data.awaitingHashes(request)
    replyTos foreach { _ ! DockerHashSuccessResponse(response.dockerHash, request) }
    val updatedData = data.copy(awaitingHashes = data.awaitingHashes - request, mappings = data.mappings + (request.dockerImageID -> response.dockerHash))
    stay using updatedData
  }

  private def failEverything(reason: Throwable, data: WorkflowDockerLookupActorData) = {
    data.enqueued foreach { case (request, replyTos) =>
      replyTos foreach { _ ! WorkflowDockerLookupFailure(reason, request) }
    }
  }

  def handleLookupSuccess(response: DockerHashSuccessResponse, data: RunningData) = {
    val dockerHashStoreEntry = DockerHashStoreEntry(workflowId.toString, response.request.dockerImageID.fullName, response.dockerHash.algorithmAndHash)
    databaseInterface.addDockerHashStoreEntries(Seq(dockerHashStoreEntry)) onComplete {
      case Success(_) => self ! DockerHashStoreSuccess(response)
      case Failure(ex) => self ! DockerHashStoreFailure(response.request, ex)
    }
    stay()
  }

  def handleLookupFailure(dockerResponse: DockerHashFailureResponse, data: RunningData) = {
    val response = WorkflowDockerLookupFailure(new Exception(dockerResponse.reason), dockerResponse.request)
    val request = dockerResponse.request
    data.awaitingHashes(request) foreach { _ ! response }

    val updatedData = data.copy(awaitingHashes = data.awaitingHashes - request)
    stay using updatedData
  }

  override protected def onTimeout(message: Any, to: ActorRef): Unit = self ! DockerHashActorTimeout
}

object WorkflowDockerLookupActor {
  /* States */
  sealed trait WorkflowDockerLookupActorState
  case object LoadingCache extends WorkflowDockerLookupActorState
  case object Running extends WorkflowDockerLookupActorState
  case object Failed extends WorkflowDockerLookupActorState
  val FailedException = new Exception(s"The service responsible for workflow level docker hash resolution failed to restart when the server restarted. Subsequent docker tags for this workflow won't be resolved.")

  /* Internal ADTs */
  case class DockerRequestContext(dockerHashRequest: DockerHashRequest, replyTo: ActorRef)
  sealed trait DockerHashStoreResponse
  case class DockerHashStoreSuccess(successResponse: DockerHashSuccessResponse) extends DockerHashStoreResponse
  case class DockerHashStoreFailure(dockerHashRequest: DockerHashRequest, reason: Throwable) extends DockerHashStoreResponse
  case class DockerHashStoreLoadingSuccess(dockerMappings: Map[String, String])
  case class DockerHashStoreLoadingFailure(reason: Throwable)
  case class DockerHashActorTimeout(message: String)

  /* Responses */
  final case class WorkflowDockerLookupFailure(reason: Throwable, request: DockerHashRequest)

  def props(workflowId: WorkflowId, dockerHashingActor: ActorRef, startMode: StartMode) = {
    Props(new WorkflowDockerLookupActor(workflowId, dockerHashingActor, startMode))
  }

  object WorkflowDockerLookupActorData {
    def emptyLoadingCacheData = LoadingCacheData(queuedRequests = Map.empty)
    def emptyRunningData = RunningData(awaitingHashes = Map.empty, mappings = Map.empty)
  }

  sealed trait WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorData
    def enqueued: Map[DockerHashRequest, List[ActorRef]]
  }

  /**
    *
    * @param queuedRequests Requests from the `JobPreparationActor` that are queued up until this actor finishes loading
    *                       the cache of docker tag mappings from the database.
    */
  case class LoadingCacheData(queuedRequests: Map[DockerHashRequest, List[ActorRef]]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorData = {
      val newValue = if (queuedRequests.contains(request)) {
        replyTo :: queuedRequests(request)
      } else {
        List(replyTo)
      }
      this.copy(queuedRequests = queuedRequests + (request -> newValue))
    }

    override def enqueued: Map[DockerHashRequest, List[ActorRef]] = queuedRequests
  }


  /**
    * @param awaitingHashes Requests that have gone out to the `DockerHashActor` which are awaiting hash resolution.
    * @param mappings Established mappings from `DockerImageIdentifier`s to `DockerHashResult`s.
    */
  case class RunningData(awaitingHashes: Map[DockerHashRequest, List[ActorRef]], mappings: Map[DockerImageIdentifier, DockerHashResult]) extends WorkflowDockerLookupActorData {
    def enqueue(request: DockerHashRequest, replyTo: ActorRef): WorkflowDockerLookupActorData = {
      val newValue = if (awaitingHashes.contains(request)) {
        replyTo :: awaitingHashes(request)
      } else {
        List(replyTo)
      }
      this.copy(awaitingHashes = awaitingHashes + (request -> newValue))
    }

    override def enqueued: Map[DockerHashRequest, List[ActorRef]] = awaitingHashes
  }
}
