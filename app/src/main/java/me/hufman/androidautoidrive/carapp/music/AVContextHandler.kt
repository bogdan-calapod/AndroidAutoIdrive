package me.hufman.androidautoidrive.carapp.music

import android.util.Log
import de.bmw.idrive.BMWRemoting
import de.bmw.idrive.BMWRemotingServer
import me.hufman.androidautoidrive.GraphicsHelpers
import me.hufman.androidautoidrive.music.MusicAppInfo
import me.hufman.androidautoidrive.music.MusicController
import me.hufman.idriveconnectionkit.android.IDriveConnectionListener

class AVContextHandler(val carConnection: BMWRemotingServer, val controller: MusicController, val graphicsHelpers: GraphicsHelpers, val musicAppMode: MusicAppMode) {
	val MY_IDENT = "me.hufman.androidautoidrive.music"  // AM and AV ident string
	val TAG = "AVContextHandler"
	var avHandle: Int? = null
	@Volatile var currentContext = false  // whether we are the current playing app.. the car doesn't grantConnection if we are already connected

	/**
	 * Creates the avHandle
	 */
	fun createAvHandle() {
		if (avHandle != null) {
			// already done
			return
		}
		val instanceId = IDriveConnectionListener.instanceId
		if (instanceId == null) {
			Log.w(TAG, "instanceId is null! skipping av handle creation for now")
		} else {
			Log.d(TAG, "instanceId == ${IDriveConnectionListener.instanceId}")
			synchronized(carConnection) {
				avHandle = carConnection.av_create(instanceId, MY_IDENT)
			}
			Log.d(TAG, "AV handle: $avHandle")
		}
	}

	fun av_requestContext(app: MusicAppInfo) {
		controller.connectAppManually(app)  // prepare the music controller, so that av_connectionGranted can use it
		if (musicAppMode.shouldRequestAudioContext()) {
			synchronized(carConnection) {
				createAvHandle()    // make sure we have an avHandle
				val avHandle = avHandle
				if (!currentContext && avHandle != null) {
					Log.i(TAG, "Sending requestContext to car for ${app.name}")
					carConnection.av_requestConnection(avHandle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT)
				} else if (!currentContext && avHandle == null) {
					Log.i(TAG, "avHandle is not set up yet, not requesting context")
				}
			}
			if (currentContext || avHandle == null) {
				// start playback if we are the current AV context
				// or play anyways if we have the wrong instanceId
				// the car will respond with av_connectionDenied if instanceId is incorrect (null coalesced to a random guess)
				enactPlayerState(BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
				av_playerStateChanged(avHandle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
			}
		} else {
			// acting as just a fancy controller for Bluetooth music, just play the app
			enactPlayerState(BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY)
		}
	}

	fun av_connectionGranted(handle: Int?, connectionType: BMWRemoting.AVConnectionType?) {
		Log.i(TAG, "Car declares current audio connection to us")
		currentContext = true

		val desiredAppInfo = controller.currentAppInfo
		if (desiredAppInfo != null && controller.currentAppController == null) {
			// MusicController wants to play an app, but the controller isn't ready yet
			controller.connectAppAutomatically(desiredAppInfo)
		}
		// otherwise, the controller.currentApp was set in an av_requestContext call
	}

	fun av_requestPlayerState(handle: Int?, connectionType: BMWRemoting.AVConnectionType?, playerState: BMWRemoting.AVPlayerState?) {
		Log.i(TAG, "Received requestPlayerState $playerState")
		if (playerState != null) {
			enactPlayerState(playerState)
			// slightly cheating, telling the car that we are playing without being certain the app is connected
			av_playerStateChanged(handle, connectionType, playerState)
		}
	}

	private fun av_playerStateChanged(handle: Int?, connectionType: BMWRemoting.AVConnectionType?, playerState: BMWRemoting.AVPlayerState?) {
		// helper function to help synchronize car accesses
		synchronized(carConnection) {
			if (handle != null) {
				carConnection.av_playerStateChanged(handle, BMWRemoting.AVConnectionType.AV_CONNECTION_TYPE_ENTERTAINMENT, playerState)
			}
		}
	}

	private fun enactPlayerState(playerState: BMWRemoting.AVPlayerState) {
		when (playerState) {
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PAUSE -> controller.pause()
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_STOP -> controller.pause()
			BMWRemoting.AVPlayerState.AV_PLAYERSTATE_PLAY -> controller.play()
		}
	}

	fun av_connectionDeactivated(handle: Int?, connectionType: BMWRemoting.AVConnectionType?) {
		// the car is requesting the current app stop so that a different app can play
		// either another app within our own app (which won't trigger connectionGranted)
		// or another source entirely{
		Log.i(TAG, "Deactivating app currently-connected ${controller.currentAppInfo?.name}")
		controller.pause()
		currentContext = false
	}

	fun av_multimediaButtonEvent(handle: Int?, event: BMWRemoting.AVButtonEvent?) {
		when (event) {
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_UP -> controller.skipToNext()
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_DOWN -> controller.skipToPrevious()
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_LONG_UP -> controller.startFastForward()
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_LONG_DOWN -> controller.startRewind()
			BMWRemoting.AVButtonEvent.AV_EVENT_SKIP_LONG_STOP -> controller.stopSeeking()
		}
	}
}