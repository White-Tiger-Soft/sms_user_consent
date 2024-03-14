package dev.pharsh.sms_user_consent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.credentials.Credential
import com.google.android.gms.auth.api.credentials.Credentials
import com.google.android.gms.auth.api.credentials.HintRequest
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result


/** SmsUserConsentPlugin */
class SmsUserConsentPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private lateinit var mActivity: Activity

    companion object {
        private const val CREDENTIAL_PICKER_REQUEST = 1
        private const val SMS_CONSENT_REQUEST = 2
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "sms_user_consent")
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "requestPhoneNumber" -> {
                requestHint()
                result.success(null)
            }

            "requestSms" -> {
                val listenToBroadcastsFromOtherApps = true
                val receiverFlags = if (listenToBroadcastsFromOtherApps) {
                    ContextCompat.RECEIVER_EXPORTED
                } else {
                    ContextCompat.RECEIVER_NOT_EXPORTED
                }

                SmsRetriever.getClient(mActivity.applicationContext)
                    .startSmsUserConsent(call.argument("senderPhoneNumber"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mActivity.registerReceiver(
                        smsVerificationReceiver,
                        IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                        SmsRetriever.SEND_PERMISSION,
                        null,
                        receiverFlags,
                    )
                } else {
                    mActivity.registerReceiver(
                        smsVerificationReceiver,
                        IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                        SmsRetriever.SEND_PERMISSION,
                        null,
                    )
                }
                result.success(null)
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        mActivity = binding.activity

        binding.addActivityResultListener { requestCode, resultCode, data ->
            when (requestCode) {
                CREDENTIAL_PICKER_REQUEST -> {// Obtain the phone number from the result
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        channel.invokeMethod(
                            "selectedPhoneNumber",
                            data.getParcelableExtra<Credential>(Credential.EXTRA_KEY)?.id
                        )
                    } else {
                        channel.invokeMethod("selectedPhoneNumber", null)
                    }
                    true
                }

                SMS_CONSENT_REQUEST -> {// Obtain the phone number from the result
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        try {
                            channel.invokeMethod(
                                "receivedSms",
                                data.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE)
                            )
                            mActivity.unregisterReceiver(smsVerificationReceiver)
                        } catch (e: Exception) {
                            // Avoid crash if receiver is not registered
                        }
                    } else {
                        // Consent denied. User can type OTC manually.
                        try {
                            // Consent denied. User can type OTC manually.
                            channel.invokeMethod("receivedSms", null)
                            mActivity.unregisterReceiver(smsVerificationReceiver)
                        } catch (e: Exception) {
                            // Avoid crash if receiver is not registered
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {}

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}

    override fun onDetachedFromActivity() {}

    /// Construct a request for phone numbers and show the picker
    private fun requestHint() {
        mActivity.startIntentSenderForResult(
            Credentials.getClient(mActivity).getHintPickerIntent(
                HintRequest.Builder()
                    .setPhoneNumberIdentifierSupported(true)
                    .build()
            ).intentSender,
            CREDENTIAL_PICKER_REQUEST,
            null, 0, 0, 0
        )
    }

    private val smsVerificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (SmsRetriever.SMS_RETRIEVED_ACTION == intent.action) {
                val extras = intent.extras ?: return
                val smsRetrieverStatus = extras.get(SmsRetriever.EXTRA_STATUS) as Status

                when (smsRetrieverStatus.statusCode) {
                    CommonStatusCodes.SUCCESS -> {
                        try {
                            val contentIntent =
                                extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT);
                            if (contentIntent == null) {
                                return;
                            }
                            // remove the grant URI permissions in the untrusted Intent
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                contentIntent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                contentIntent.removeFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            }

                            val packageManager = mActivity.packageManager;
                            val name = contentIntent.resolveActivity(packageManager);
                            val flags = contentIntent.flags;

                            if (name != null &&
                                name.packageName == "com.google.android.gms" &&
                                name.className == "com.google.android.gms.auth.api.phone.ui.UserConsentPromptActivity" &&
                                (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0 &&
                                (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0
                            ) {
                                mActivity.startActivityForResult(
                                    contentIntent,
                                    SMS_CONSENT_REQUEST
                                );
                            }
                        } catch (e: ActivityNotFoundException) {
                            // Handle the exception ...
                        }
                    }

                    CommonStatusCodes.TIMEOUT -> {
                        // Time out occurred, handle the error.
                    }
                }
            }
        }
    }
}
