package im.tox.tox4j.core.callbacks

import java.util
import java.util.Random

import im.tox.tox4j.core.ToxCore
import im.tox.tox4j.core.enums.{ ToxConnection, ToxFileControl, ToxFileKind, ToxMessageType }
import im.tox.tox4j.testing.autotest.{ AliceBobTest, AliceBobTestBase }
import org.junit.Assert._

/**
 * This test intends to simulate the situation of file pause
 * and resume initiated by both the sending side and the receiving side.
 * - Alice initiated the file transmission and Bob accepted
 * - After sending 1/6 of the file, Alice paused the transmission
 * - Bob saw Alice's paused transmission and sent a message to request resuming
 * - Alice resumed the transmission
 * - Bob paused the transmission after receiving 1/3 of the file
 * - Alice saw Bob paused transmission and sent a message to request resuming
 * - Bob resumed the transmission and received all the data
 */
abstract class FilePauseResumeTestBase extends AliceBobTest {

  final override type State = Unit
  final override def initialState: State = ()

  protected val fileData = new Array[Byte](300 * 1371) // (TestConstants.ITERATIONS * ToxCoreConstants.MAX_CUSTOM_PACKET_SIZE)
  new Random().nextBytes(fileData)
  protected var aliceSentFileNumber = -1
  private var aliceOffset = 0L
  protected var aliceShouldPause = -1
  private var fileId = Array.ofDim[Byte](0)
  private val receivedData = new Array[Byte](fileData.length)
  private var bobSentFileNumber = -1
  private var bobOffset = 0L
  protected var bobShouldPause = -1

  abstract class Alice(name: String, expectedFriendName: String) extends ChatClient(name, expectedFriendName) {

    protected def addFriendMessageTask(friendNumber: Int, bobSentFileNumber: Int, fileId: Array[Byte], tox: ToxCore[ChatState]): Unit
    protected def addFileRecvTask(friendNumber: Int, fileNumber: Int, bobSentFileNumber: Int, bobOffset: Long, tox: ToxCore[ChatState]): Unit

    override def friendConnectionStatus(friendNumber: Int, connection: ToxConnection)(state: ChatState): ChatState = {
      if (isAlice) {
        if (connection != ToxConnection.NONE) {
          debug(s"is now connected to friend $friendNumber")
          debug(s"initiate file sending to friend $friendNumber")
          assertEquals(AliceBobTestBase.FRIEND_NUMBER, friendNumber)
          state.addTask { (tox, state) =>
            aliceSentFileNumber = tox.fileSend(friendNumber, ToxFileKind.DATA, fileData.length,
              Array.ofDim[Byte](0), ("file for " + expectedFriendName + ".png").getBytes)
            fileId = tox.fileGetFileId(friendNumber, aliceSentFileNumber)
            state
          }
        } else {
          state
        }
      } else {
        if (connection != ToxConnection.NONE) {
          debug(s"is now connected to friend $friendNumber")
          assertEquals(AliceBobTestBase.FRIEND_NUMBER, friendNumber)
        }
        state
      }
    }

    override def fileRecv(friendNumber: Int, fileNumber: Int, kind: Int, fileSize: Long, filename: Array[Byte])(state: ChatState): ChatState = {
      assertTrue(isBob)
      debug(s"received file send request $fileNumber from friend number $friendNumber current offset $bobOffset")
      assertEquals(AliceBobTestBase.FRIEND_NUMBER, friendNumber)
      assertEquals(ToxFileKind.DATA, kind)
      assertEquals(s"file for $name.png", new String(filename))
      bobSentFileNumber = fileNumber
      state.addTask { (tox, state) =>
        addFileRecvTask(friendNumber, fileNumber, bobSentFileNumber, bobOffset, tox)
        state
      }
    }

    override def fileChunkRequest(friendNumber: Int, fileNumber: Int, position: Long, length: Int)(state: ChatState): ChatState = {
      assertTrue(isAlice)
      debug(s"got request for ${length}B from $friendNumber for file $fileNumber at $position")
      assertTrue(length >= 0)
      if (length == 0) {
        aliceSentFileNumber = -1
        debug("finish transmission")
        state.finish
      } else {
        if (aliceShouldPause != 0) {
          val nextState = state.addTask { (tox, state) =>
            debug(s"sending ${length}B to $friendNumber from position $position")
            tox.fileSendChunk(friendNumber, fileNumber, position,
              util.Arrays.copyOfRange(fileData, position.toInt, Math.min(position.toInt + length, fileData.length)))
            state
          }
          aliceOffset += length
          if (aliceOffset >= fileData.length / 3 && aliceShouldPause == -1) {
            aliceShouldPause = 0
            nextState.addTask { (tox, state) =>
              tox.fileControl(friendNumber, fileNumber, ToxFileControl.PAUSE)
              debug("pause file transmission")
              state
            }
          } else {
            nextState
          }
        } else {
          state
        }
      }
    }

    override def fileRecvControl(friendNumber: Int, fileNumber: Int, control: ToxFileControl)(state: ChatState): ChatState = {
      if (isAlice) {
        debug("receive file control from Bob")
        if (control == ToxFileControl.RESUME) {
          if (aliceShouldPause != 0) {
            debug("bob accept file transmission request")
          } else {
            debug("see bob resume file transmission")
            aliceShouldPause = 1
          }
          state
        } else if (control == ToxFileControl.PAUSE) {
          state.addTask { (tox, state) =>
            aliceShouldPause = 0
            tox.sendMessage(friendNumber, ToxMessageType.NORMAL, 0, "Please resume the file transfer".getBytes)
            state
          }
        } else {
          state
        }
      } else {
        if (control == ToxFileControl.PAUSE) {
          debug("see alice pause file transmission")
          state.addTask { (tox, state) =>
            debug("request to resume file transmission")
            tox.sendMessage(friendNumber, ToxMessageType.NORMAL, 0, "Please resume the file transfer".getBytes)
            state
          }
        } else {
          state
        }
      }
    }

    override def fileRecvChunk(friendNumber: Int, fileNumber: Int, position: Long, data: Array[Byte])(state: ChatState): ChatState = {
      assertTrue(isBob)
      debug(s"receive file chunk from position $position of length ${data.length} shouldPause $bobShouldPause")
      if (data.length == 0 && receivedData.length == bobOffset) {
        assertArrayEquals(fileData, receivedData)
        debug("finish transmission")
        state.finish
      } else {
        System.arraycopy(data, 0, receivedData, position.toInt, data.length)
        bobOffset += data.length
        if (bobOffset >= fileData.length / 2 && bobShouldPause == -1) {
          bobShouldPause = 0
          state.addTask { (tox, state) =>
            debug("send file control to pause")
            tox.fileControl(friendNumber, bobSentFileNumber, ToxFileControl.PAUSE)
            state
          }
        } else {
          state
        }
      }
    }

    override def friendMessage(friendNumber: Int, newType: ToxMessageType, timeDelta: Int, message: Array[Byte])(state: ChatState): ChatState = {
      debug(s"received a message: ${new String(message)}")
      assertEquals("Please resume the file transfer", new String(message))
      state.addTask { (tox, state) =>
        addFriendMessageTask(friendNumber, bobSentFileNumber, fileId, tox)
        state
      }
    }

  }

}
