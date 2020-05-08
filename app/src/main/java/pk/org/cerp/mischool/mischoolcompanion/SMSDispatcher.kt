package pk.org.cerp.mischool.mischoolcompanion

import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import com.beust.klaxon.Klaxon
import com.evernote.android.job.Job
import com.evernote.android.job.JobCreator
import com.evernote.android.job.JobRequest
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.*

class SMSDispatcher : JobCreator {

    override fun create(tag: String): Job? {

        return when(tag) {
            SMSJob.JOB_TAG -> {
                Log.d(TAG, "Scheduling smsjob")
                SMSJob()
            }
            else -> null
        }
    }
}

class SMSJob : Job() {

    companion object {
        val JOB_TAG = "SMS_JOB"

        fun scheduleJob() {
            Log.d(TAG, "inside scheduleJob function")
            JobRequest.Builder(JOB_TAG)
                    .setExact(60000)
                    .setUpdateCurrent(true)
                    .build()
                    .schedule()
        }

        fun scheduleJobImmediate() {
            Log.d(TAG, "inside scheduleJob function")
            JobRequest.Builder(JOB_TAG)
                    .startNow()
                    .setUpdateCurrent(true)
                    .build()
                    .schedule()
        }


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

    override fun onRunJob(params: Params): Result {

        var reschedule = true
        return try {
            Log.d(TAG, "doing run job")
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

            Result.SUCCESS
        }
        catch(e : Exception) {
            Log.e(TAG, e.message)
            Result.FAILURE
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
            sendSMS(p.text, p.number)
            Thread.sleep(1000)
        }

    }

    fun sendSMS(text: String, phoneNumber: String) {
        try {

            // check permission first
            val smsManager = SmsManager.getDefault();

            val messages = smsManager.divideMessage(text)

            val currentTime = DateFormat.getDateTimeInstance().format(Date())

            Log.d(TAG, "size of messages: ${messages.size}")

            if(messages.size > 1) {
                Log.d(TAG, "SENDING MULTIPART")
                smsManager.sendMultipartTextMessage(phoneNumber, null, messages, null, null)
                updateLogText("sent multipart message to: $phoneNumber at $currentTime")
            }
            else {
                smsManager.sendTextMessage(phoneNumber, null, text, null, null)
                updateLogText("sent message to: $phoneNumber at $currentTime")
            }

            // updateLogText("message sent")
        } catch( e: Exception) {
            Log.d(TAG, e.message)
            val currentTime = DateFormat.getDateTimeInstance().format(Date())
            updateLogText("ERROR sending to $phoneNumber: ${e.message} at $currentTime")
        }

    }

    fun messageHistory() : Pair<Int, Int> {

        val unixTime = System.currentTimeMillis()

        try {

            val min_time = unixTime - (15 * 60 * 1000)
            val cursor = this.context.contentResolver.query(
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

        // first read the file as json

        Log.d(TAG, "appending messages to file.....")

        val file = File(context.filesDir, "$filename")

        var content : String? = null

        if(file.exists()) {
            val bytes = file.readBytes()
            content = String(bytes)
            Log.d(TAG,"content of pending messages is $content")
        }

        return if(content == null) emptyList<SMSItem>() else Klaxon().parseArray<SMSItem>(content).orEmpty()
    }

    fun writeMessageToLogFile(message : String) {

        val file = File(context.filesDir, "$logFileName")

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

        val file = File(context.filesDir, "$filename")

        Log.d(TAG, "messages length is ${messages.size}")
        val res = Klaxon().toJsonString(messages)
        file.writeBytes(res.toByteArray())

        Log.d(TAG, "DONE writing file")
    }

    fun getMaxMessagesPerMinute() {

    }

}

