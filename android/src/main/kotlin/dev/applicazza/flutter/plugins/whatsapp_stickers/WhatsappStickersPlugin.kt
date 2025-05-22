package dev.applicazza.flutter.plugins.whatsapp_stickers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import java.io.File

/** WhatsappStickersPlugin */
class WhatsappStickersPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {

  private lateinit var channel: MethodChannel
  private var context: Context? = null
  private var activity: Activity? = null
  private var result: Result? = null

  private val ADD_PACK = 200

  companion object {
    private const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    private const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"

    @JvmStatic
    fun getContentProviderAuthorityURI(context: Context): Uri {
      return Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(getContentProviderAuthority(context))
        .appendPath(StickerContentProvider.METADATA)
        .build()
    }

    @JvmStatic
    fun getContentProviderAuthority(context: Context): String {
      return context.packageName + ".stickercontentprovider"
    }
  }

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.applicationContext
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "whatsapp_stickers")
    channel.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.activity = binding.activity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    // no-op
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    this.activity = binding.activity
    binding.addActivityResultListener(this)
  }

  override fun onDetachedFromActivity() {
    this.activity = null
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    this.result = result
    when (call.method) {
      "getPlatformVersion" -> result.success("Android ${android.os.Build.VERSION.RELEASE}")
      "isWhatsAppInstalled" -> result.success(context?.let { WhitelistCheck.isWhatsAppInstalled(it) })
      "isWhatsAppConsumerAppInstalled" -> result.success(WhitelistCheck.isWhatsAppConsumerAppInstalled(context?.packageManager))
      "isWhatsAppSmbAppInstalled" -> result.success(WhitelistCheck.isWhatsAppSmbAppInstalled(context?.packageManager))
      "isStickerPackInstalled" -> {
        val stickerPackIdentifier = call.argument<String>("identifier")
        if (stickerPackIdentifier != null && context != null) {
          val installed = WhitelistCheck.isWhitelisted(context!!, stickerPackIdentifier)
          result.success(installed)
        } else {
          result.error("invalid_argument", "identifier is null", null)
        }
      }
      "sendToWhatsApp" -> {
        try {
          val stickerPack: StickerPack = ConfigFileManager.fromMethodCall(context, call)
          ConfigFileManager.addNewPack(context, stickerPack)
          context?.let { StickerPackValidator.verifyStickerPackValidity(it, stickerPack) }

          val ws = WhitelistCheck.isWhatsAppConsumerAppInstalled(context?.packageManager)
          if (!(ws || WhitelistCheck.isWhatsAppSmbAppInstalled(context?.packageManager))) {
            throw InvalidPackException(InvalidPackException.OTHER, "WhatsApp is not installed on target device!")
          }

          val whatsAppPackage = if (ws) WhitelistCheck.CONSUMER_WHATSAPP_PACKAGE_NAME else WhitelistCheck.SMB_WHATSAPP_PACKAGE_NAME
          val intent = createIntentToAddStickerPack(
            getContentProviderAuthority(context!!),
            stickerPack.identifier,
            stickerPack.name
          )

          activity?.startActivityForResult(Intent.createChooser(intent, "ADD Sticker"), ADD_PACK)
        } catch (e: InvalidPackException) {
          result.error(e.code, e.message, null)
        } catch (e: ActivityNotFoundException) {
          result.error("activity_not_found", "Sticker pack not added. Ensure WhatsApp is installed.", null)
        }
      }
      else -> result.notImplemented()
    }
  }

  private fun createIntentToAddStickerPack(authority: String, identifier: String, name: String): Intent {
    return Intent().apply {
      action = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
      putExtra(EXTRA_STICKER_PACK_ID, identifier)
      putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority)
      putExtra(EXTRA_STICKER_PACK_NAME, name)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode == ADD_PACK) {
      when (resultCode) {
        Activity.RESULT_CANCELED -> {
          val error = data?.getStringExtra("validation_error")
          if (error != null) {
            result?.error("error", error, null)
          } else {
            result?.error("cancelled", "cancelled", null)
          }
        }
        Activity.RESULT_OK -> {
          val bundle = data?.extras
          when {
            bundle?.containsKey("add_successful") == true -> result?.success("add_successful")
            bundle?.containsKey("already_added") == true -> result?.error("already_added", "already_added", null)
            else -> result?.success("success")
          }
        }
        else -> result?.success("unknown")
      }
      return true
    }
    return false
  }
}