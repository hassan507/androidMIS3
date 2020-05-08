package pk.org.cerp.mischool.mischoolcompanion

import android.annotation.TargetApi
import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.annotation.RequiresApi
import android.telephony.SmsManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import com.beust.klaxon.Klaxon
import java.io.File
import java.text.DateFormat
import java.util.*


class SMSDispatcherService : Service() {


    @TargetApi(Build.VERSION_CODES.O)
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Toast.makeText(this, "Notification Service started by user.", Toast.LENGTH_LONG).show()
        Log.d(TAG, "Services is working")
        MyFunc()
        return Service.START_STICKY
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
//    override fun onHandleIntent(intent: Intent?) {
//
//        try {
//            Log.d(TAG, "inside on handle intent")
//            if (intent != null) {
//                //Log.d(TAG, intent.dataString.toString())
//                MyFunc();
//            };
//        } catch(e : Exception) {
//            Log.d(TAG, e.message)
//        }
//    }

    fun sendAllSMS(messages : List<SMSItem>) {
        for(p in messages) {
            Log.d(TAG, "send " + p.text + " to " + p.number)
            sendSMS(p)
            Thread.sleep(100)
        }

        Toast.makeText(applicationContext, messages.size.toString() + " messages Sent", Toast.LENGTH_SHORT).show()

    }



    fun sentMessages() : Pair<Int, Int> {

        val unixTime = System.currentTimeMillis()

        try {

            val min_time = unixTime - (15 * 60 * 1000)
            val cursor = contentResolver.query(
                    Uri.parse("content://sms/sent"),
                    arrayOf("date"),
                    "date > $min_time",
                    null,
                    null
            )


            return if (cursor.moveToFirst()) {
                var messages_past_minute = 0
                var messages_past_15_min = 0

                do {

                    val date = cursor.getLong(cursor.getColumnIndex("date"))
                    val diff = (unixTime - date) / 1000L

                    if(diff <= 60) messages_past_minute++

                    messages_past_15_min++


                } while (cursor.moveToNext())

                return Pair(messages_past_minute, messages_past_15_min)
            } else {
                Log.d(TAG, "couldnt move to first...")
                return Pair(0, 0)
            }

        }
        catch(e : Exception) {
            Log.e(TAG, e.message)
            return Pair(0, 0)
        }

    }

    public fun MyFunc(){
        var reschedule = true
        return try {
            Log.d(TAG, "doing run job using service")
            // updateLogText("doing run job")

            val pending = readMessagesFromFile()
            val num_messages = pending.size

            val history = messageHistory()
            val last_min_messages = history.first
            val last_15_min_messages = history.second
            val max_per_minute = 30 // this should be variable depending on android version
            val max_per_pta_rule = 12
            val max_sendable = max_per_minute - last_min_messages

            Log.d(TAG, "${pending.size} items queued")
            updateLogText("${pending.size} items queued")


            // we assume that sending messages will not error for now.
            // because when they do error they tend to show up in the messages app for manual retry

            val next_list = when {
                last_min_messages > max_per_minute ->  {
                    Log.d(TAG, "too many messages sent last minute. waiting until next round")
                    pending
                }
                (last_min_messages + num_messages) < max_per_minute -> {
                    Log.d(TAG, "sending all messages right now")
                    sendBatchSMS(pending)
                    emptyList<SMSItem>()
                }
                (last_15_min_messages + num_messages) in 30..185 -> {
                    // we don't need to worry about the pta rule, so fire off max per minute this round.
                    Log.d(TAG, "between 30 and 185 messages")
                    sendBatchSMS(pending.take(max_sendable))
                    pending.drop(max_sendable)
                }
                (num_messages + last_15_min_messages) > 200 -> {
                    // fire the messages off at a rate that cares about the pta limit (200 / 15 min) 12 per minute...
                    sendBatchSMS(pending.take(max_per_pta_rule))
                    pending.drop(max_per_pta_rule)
                }
                else -> {
                    Log.d(TAG, "unforseen combination of numbers. last_min: $last_min_messages, 15 min: $last_15_min_messages, pending: $num_messages")
                    sendBatchSMS(pending.take(max_sendable))
                    pending.drop(max_sendable)
                }
            }

            writeMessagesToFile(next_list)
            reschedule = next_list.isNotEmpty()


        }
        catch(e : Exception) {
            //Log.e(TAG, e.message)

        } finally {
            if(reschedule) SMSJob.scheduleJob() else {
                Log.d(TAG, "done sending messages!")
                updateLogText("all messages sent")
            }
        }
    }

    fun sendBatchSMS(messages : List<SMSItem>) {
        for(p in messages) {
            Log.d(TAG, "send " + p.text + " to " + p.number)
            sendSMS(p)
            Thread.sleep(1000)
        }

    }

    fun sendSMS(p:SMSItem) {
        try {

            // check permission first
            val smsManager = SmsManager.getDefault();

            val messages = smsManager.divideMessage(p.text)

            val currentTime = DateFormat.getDateTimeInstance().format(Date())

            Log.d(TAG, "size of messages: ${messages.size}")

            val sentPI = PendingIntent.getBroadcast(this, 0, Intent("SENT"), 0)

            val broadCastReceiver = object : BroadcastReceiver() {
                override fun onReceive(contxt: Context?, intent: Intent?) {
                    Log.d(TAG,"inside receive")
                    val resultCode = getResultCode();
                    if (resultCode == Activity.RESULT_OK) {
                        p.status = "SENT"
                        Toast.makeText(getBaseContext(), "SMS sent",Toast.LENGTH_SHORT).show();
                    }else if(resultCode == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
                        p.status = "Failed"
                        Toast.makeText(getBaseContext(), "error",Toast.LENGTH_SHORT).show();
                    }else if(resultCode == SmsManager.RESULT_ERROR_NO_SERVICE) {
                        p.status = "Failed"
                        Toast.makeText(getBaseContext(), "Generic failure",Toast.LENGTH_SHORT).show();
                    }else if(resultCode == SmsManager.RESULT_ERROR_NULL_PDU) {
                        p.status = "Failed"
                        Toast.makeText(getBaseContext(), "No service",Toast.LENGTH_SHORT).show();
                    }else if(resultCode == SmsManager.RESULT_ERROR_RADIO_OFF) {
                        p.status = "Failed"
                        Toast.makeText(getBaseContext(), "Radio off",Toast.LENGTH_SHORT).show();
                    }

                }
            }
            registerReceiver( broadCastReceiver ,IntentFilter("SENT"));

            if(messages.size > 1) {
                Log.d(TAG, "SENDING MULTIPART")
                smsManager.sendMultipartTextMessage(p.number, null, messages, null, null)
                updateLogText("sent multipart message to: ${p.number} at $currentTime")
            }
            else {
                smsManager.sendTextMessage(p.number, null, p.text, sentPI, null)
                updateLogText("sent message to: ${p.number} at $currentTime")
            }
             updateLogText("message sent")
        } catch( e: Exception) {
            Log.d(TAG, e.message)
            val currentTime = DateFormat.getDateTimeInstance().format(Date())
            updateLogText("ERROR sending to ${p.number}: ${e.message} at $currentTime")
        }

    }



    fun messageHistory() : Pair<Int, Int> {

        val unixTime = System.currentTimeMillis()

        try {

            val min_time = unixTime - (15 * 60 * 1000)
            val cursor = this.applicationContext.contentResolver.query(
                    Uri.parse("content://sms/sent"),
                    arrayOf("date"),
                    "date > $min_time",
                    null,
                    null
            )


            return if (cursor.moveToFirst()) {
                var messages_past_minute = 0
                var messages_past_15_min = 0

                do {

                    val date = cursor.getLong(cursor.getColumnIndex("date"))
                    val diff = (unixTime - date) / 1000L

                    if(diff <= 60) messages_past_minute++

                    messages_past_15_min++


                } while (cursor.moveToNext())

                return Pair(messages_past_minute, messages_past_15_min)
            } else {
                Log.d(TAG, "couldnt move to first...")
                return Pair(0, 0)
            }

        }
        catch(e : Exception) {
            Log.e(TAG, e.message)
            return Pair(0, 0)
        }

    }

    fun readMessagesFromFile() : List<SMSItem> {

        Log.d(TAG, "appending messages to file.....")

        val file = File(applicationContext.filesDir, "$filename")

        var content : String? = null

        if(file.exists()) {
            val bytes = file.readBytes()
            content = String(bytes)
            Log.d(TAG,"content of pending messages is $content")
        }

        return if(content == null) emptyList<SMSItem>() else Klaxon().parseArray<SMSItem>(content).orEmpty()
    }

    fun writeMessageToLogFile(message : String) {

        val file = File(applicationContext.filesDir, "$logFileName")

        Log.d(TAG, "appending messages to log file.....")

        var content = if(file.exists()) {
            val bytes = file.readBytes()
            String(bytes) + "\n" + message
        } else {
            message
        }

        file.writeBytes(content.toByteArray())

        Log.d(TAG, "DONE writing")

    }

    fun writeMessagesToFile(messages : List<SMSItem>) {

        val file = File(applicationContext.filesDir, "$filename")

        Log.d(TAG, "messages length is ${messages.size}")
        val res = Klaxon().toJsonString(messages)
        file.writeBytes(res.toByteArray())

        Log.d(TAG, "DONE writing file")
    }

    fun updateLogText(text : String) {
        // here we'll write logs to a file
        // on the ui we'll read the file and display it.

        try {

            // val activityThreadClass = Class.forName("pk.org.cerp.mischool.mischoolcompanion.MainActivity")

            // (context as MainActivity).updateLogText("doing run job")

            writeMessageToLogFile(text)
        }
        catch (e : Exception) {
            Log.e(TAG, e.message)
        }
    }

}