package androidx.media3.session

import androidx.media3.common.Player
import com.google.common.util.concurrent.ListenableFuture

// TODO: upstream solution
fun MediaSession.addToCommandQueueThenFlush(controller: MediaSession.ControllerInfo,
                                            task: () -> ListenableFuture<Void>) {
    val ccm = (token.binder as MediaSessionStub).connectedControllersManager
    ccm.addToCommandQueue(
        controller, Player.COMMAND_INVALID, task)
    impl.applicationHandler.post { ccm.flushCommandQueue(controller) }
}
