package im.tox.tox4j.testing.autotest

import com.typesafe.scalalogging.Logger
import im.tox.tox4j.core.ToxCore
import im.tox.tox4j.testing.ToxTestMixin
import im.tox.tox4j.testing.autotest.AliceBobTestBase.Chatter
import org.scalatest.FunSuite
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scalaz.Scalaz._

object AliceBobTestBase {
  val FRIEND_NUMBER = 10

  final case class Chatter[T](
    tox: ToxCore[ChatStateT[T]],
    client: ChatClientT[T],
    state: ChatStateT[T]
  )
}

abstract class AliceBobTestBase extends FunSuite with ToxTestMixin {

  protected val logger = Logger(LoggerFactory.getLogger(classOf[AliceBobTestBase]))

  protected type State
  protected type ChatState = ChatStateT[State]
  protected type ChatClient = ChatClientT[State]

  protected def initialState: State

  protected def newChatClient(name: String, expectedFriendName: String): ChatClient

  private def getTopLevelMethod(stackTrace: Seq[StackTraceElement]): String = {
    stackTrace
      .filter(_.getClassName == classOf[AliceBobTest].getName)
      .lastOption
      .fold("<unknown>")(_.getMethodName)
  }

  @tailrec
  private def mainLoop(clients: Seq[Chatter[State]]): Unit = {
    val nextState = clients.map {
      case Chatter(tox, client, state) =>
        Chatter[State](tox, client, state |> tox.iterate |> (_.runTasks(tox)))
    }

    val interval = nextState.map(_.tox.iterationInterval).max
    Thread.sleep(interval)

    if (nextState.exists(_.state.chatting)) {
      mainLoop(nextState)
    }
  }

  protected def runAliceBobTest(withTox: (ToxCore[ChatState] => Unit) => Unit): Unit = {
    val method = getTopLevelMethod(Thread.currentThread.getStackTrace)
    logger.info(s"[${Thread.currentThread.getId}] --- ${getClass.getSimpleName}.$method")

    val aliceChat = newChatClient("Alice", "Bob")
    val bobChat = newChatClient("Bob", "Alice")

    withTox { alice =>
      withTox { bob =>
        assert(alice ne bob)

        addFriends(alice, AliceBobTestBase.FRIEND_NUMBER)
        addFriends(bob, AliceBobTestBase.FRIEND_NUMBER)

        alice.addFriendNorequest(bob.getPublicKey)
        bob.addFriendNorequest(alice.getPublicKey)

        aliceChat.expectedFriendAddress = bob.getAddress
        bobChat.expectedFriendAddress = alice.getAddress

        alice.callback(aliceChat)
        bob.callback(bobChat)

        val aliceState = aliceChat.setup(alice)(ChatStateT[State](initialState))
        val bobState = bobChat.setup(bob)(ChatStateT[State](initialState))

        mainLoop(Seq(
          Chatter(alice, aliceChat, aliceState),
          Chatter(bob, bobChat, bobState)
        ))
      }
    }
  }
}
