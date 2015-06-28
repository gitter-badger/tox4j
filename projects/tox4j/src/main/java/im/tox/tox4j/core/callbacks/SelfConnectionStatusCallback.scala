package im.tox.tox4j.core.callbacks

import im.tox.tox4j.annotations.NotNull
import im.tox.tox4j.core.ToxCore
import im.tox.tox4j.core.enums.ToxConnection

/**
 * This event is triggered whenever there is a change in the DHT connection
 * state. When disconnected, a client may choose to call [[ToxCore.bootstrap]] again, to
 * reconnect to the DHT. Note that this state may frequently change for short
 * amounts of time. Clients should therefore not immediately bootstrap on
 * receiving a disconnect.
 */
trait SelfConnectionStatusCallback[ToxCoreState] {
  /**
   * @param connectionStatus The new connection status.
   */
  def selfConnectionStatus(
    @NotNull connectionStatus: ToxConnection
  )(state: ToxCoreState): ToxCoreState = state
}
