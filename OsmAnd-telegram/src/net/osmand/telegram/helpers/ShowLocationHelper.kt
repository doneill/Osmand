package net.osmand.telegram.helpers

import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import net.osmand.aidl.map.ALatLon
import net.osmand.aidl.maplayer.point.AMapPoint
import net.osmand.telegram.TelegramApplication
import net.osmand.telegram.helpers.TelegramHelper.MessageOsmAndBotLocation
import net.osmand.telegram.helpers.TelegramUiHelper.ListItem
import net.osmand.telegram.utils.AndroidUtils
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File
import android.net.Uri
import net.osmand.telegram.R

class ShowLocationHelper(private val app: TelegramApplication) {

	companion object {
		const val MAP_LAYER_ID = "telegram_layer"
	}

	private val telegramHelper = app.telegramHelper
	private val osmandAidlHelper = app.osmandAidlHelper

	var showingLocation: Boolean = false
		private set

	fun setupMapLayer() {
		execOsmandApi {
			osmandAidlHelper.addMapLayer(MAP_LAYER_ID, "Telegram", 5.5f, null)
		}
	}

	fun showLocationOnMap(item: ListItem, stale: Boolean = false) {
		if (item.latLon == null) {
			return
		}
		execOsmandApi {
			osmandAidlHelper.showMapPoint(
				MAP_LAYER_ID,
				item.getMapPointId(),
				item.getVisibleName(),
				item.getVisibleName(),
				item.chatTitle,
				Color.WHITE,
				ALatLon(item.latLon!!.latitude, item.latLon!!.longitude),
				null,
				generatePhotoParams(if (stale) item.grayscalePhotoPath else item.photoPath, stale)
			)
		}
	}

	fun updateLocationsOnMap() {
		execOsmandApi {
			val messages = telegramHelper.getMessages()
			for (message in messages) {
				val date = telegramHelper.getLastUpdatedTime(message)
				val messageShowingTime = System.currentTimeMillis() / 1000 - date
				if (messageShowingTime > app.settings.locHistoryTime) {
					removeMapPoint(message.chatId, message)
				} else if (app.settings.isShowingChatOnMap(message.chatId)) {
					addOrUpdateLocationOnMap(message, true)
				}
			}
		}
	}

	fun addOrUpdateLocationOnMap(message: TdApi.Message, update: Boolean = false) {
		execOsmandApi {
			val chatId = message.chatId
			val chatTitle = telegramHelper.getChat(message.chatId)?.title
			val content = message.content
			if (chatTitle != null && content is TdApi.MessageLocation) {
				var userName = ""
				var photoPath: String? = null
				val date = telegramHelper.getLastUpdatedTime(message)
				val stale = System.currentTimeMillis() / 1000 - date > app.settings.staleLocTime
				val user = telegramHelper.getUser(message.senderUserId)
				if (user != null) {
					userName = "${user.firstName} ${user.lastName}".trim()
					if (userName.isEmpty()) {
						userName = user.username
					}
					if (userName.isEmpty()) {
						userName = user.phoneNumber
					}
					photoPath = if (stale) {
						telegramHelper.getUserGreyPhotoPath(user)
					} else {
						telegramHelper.getUserPhotoPath(user)
					}
				}
				if (userName.isEmpty()) {
					userName = message.senderUserId.toString()
				}
				setupMapLayer()
				val params = generatePhotoParams(photoPath, stale)
				if (update) {
					osmandAidlHelper.updateMapPoint(MAP_LAYER_ID, "${chatId}_${message.senderUserId}", userName, userName,
						chatTitle, Color.WHITE, ALatLon(content.location.latitude, content.location.longitude), null, params)
				} else {
					osmandAidlHelper.addMapPoint(MAP_LAYER_ID, "${chatId}_${message.senderUserId}", userName, userName,
						chatTitle, Color.WHITE, ALatLon(content.location.latitude, content.location.longitude), null, params)
				}
			} else if (chatTitle != null && content is MessageOsmAndBotLocation && content.isValid()) {
				val name = content.name
				setupMapLayer()
				if (update) {
					osmandAidlHelper.updateMapPoint(MAP_LAYER_ID, "${chatId}_$name", name, name,
						chatTitle, Color.WHITE, ALatLon(content.lat, content.lon), null, null)
				} else {
					osmandAidlHelper.addMapPoint(MAP_LAYER_ID, "${chatId}_$name", name, name,
						chatTitle, Color.WHITE, ALatLon(content.lat, content.lon), null, null)
				}
			}
		}
	}

	fun showChatMessages(chatId: Long) {
		execOsmandApi {
			val messages = telegramHelper.getChatMessages(chatId)
			for (message in messages) {
				addOrUpdateLocationOnMap(message)
			}
		}
	}

	fun hideChatMessages(chatId: Long) {
		hideMessages(telegramHelper.getChatMessages(chatId))
	}

	fun hideMessages(messages: List<TdApi.Message>) {
		execOsmandApi {
			for (message in messages) {
				val user = telegramHelper.getUser(message.senderUserId)
				if (user != null) {
					removeMapPoint(message.chatId, message)
				}
			}
		}
	}

	fun startShowingLocation() {
		if (!showingLocation) {
			showingLocation = true
			app.startUserLocationService()
		}
	}

	fun stopShowingLocation() {
		if (showingLocation) {
			showingLocation = false
			app.stopUserLocationService()
		}
	}

	private fun generatePhotoParams(photoPath: String?, stale: Boolean = false): Map<String, String>? {
		val photoUri = if (TextUtils.isEmpty(photoPath)) {
			val resId = if (stale) {
				R.drawable.img_user_picture
			} else {
				R.drawable.img_user_picture_active
			}
			AndroidUtils.resourceToUri(app, resId)
		} else {
			AndroidUtils.getUriForFile(app, File(photoPath))
		}
		return generatePhotoParamsFromUri(photoUri)
	}
	
	private fun generatePhotoParamsFromUri(photoUri: Uri): Map<String, String>? {
		app.grantUriPermission(
			app.settings.appToConnectPackage,
			photoUri,
			Intent.FLAG_GRANT_READ_URI_PERMISSION
		)
		return mapOf(AMapPoint.POINT_IMAGE_URI_PARAM to photoUri.toString())
	}

	private fun removeMapPoint(chatId: Long, message: TdApi.Message) {
		val content = message.content
		if (content is TdApi.MessageLocation) {
			osmandAidlHelper.removeMapPoint(MAP_LAYER_ID, "${chatId}_${message.senderUserId}")
		} else if (content is MessageOsmAndBotLocation) {
			osmandAidlHelper.removeMapPoint(MAP_LAYER_ID, "${chatId}_${content.name}")
		}
	}

	private fun execOsmandApi(action: (() -> Unit)) {
		if (!osmandAidlHelper.isOsmandConnected() && osmandAidlHelper.isOsmandBound()) {
			osmandAidlHelper.connectOsmand()
		}
		if (osmandAidlHelper.isOsmandConnected()) {
			action.invoke()
		}
	}
}