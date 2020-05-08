package pk.org.cerp.mischool.mischoolcompanion

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.beust.klaxon.Klaxon
import java.io.File


const val TAG = "MISchool-Companion"
const val MY_PERMISSIONS_SEND_SMS = 1
const val filename = "pending_messages.json"
const val logFileName = "logFile.txt"

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val intent = this.intent

        val data = intent.data
        val dataString = intent.dataString

        permissions()

        Log.d(TAG, "HELLOOOO")
        Log.d(TAG, intent.action)

        val logMessages = readLogMessages()
        val tv = findViewById<TextView>(R.id.logBox)
        tv.text = logMessages
        tv.movementMethod = ScrollingMovementMethod()

        val clearButton = findViewById<Button>(R.id.clearLogButton)
        clearButton.setOnClickListener {
            clearLogMessages()
            tv.text = readLogMessages()
        }

        if(data == null || dataString == null) {
            return
        }

        Log.d(TAG, dataString)
        val json_string = java.net.URLDecoder.decode(dataString.split("=")[1], "UTF-8")
        Log.d(TAG, json_string)

        tv.append(json_string)

        val parsed : SMSPayload? = Klaxon().parse(json_string)

        if(parsed == null) {
            return
        }

        // open file, append messages and quit
        // task which runs every minute will consume from here
        // do I need to acquire a lock on this file?

        try {
            Log.e(TAG, parsed.messages.toString())
            appendMessagesToFile(parsed.messages)
        }
        catch(e : Exception){
            Log.e(TAG, e.message)
            Log.e(TAG, e.toString())
        }

        Log.d(TAG, "scheduling....")
        try {
            //SMSJob.scheduleJobImmediate()
            //MyFunc()
            startService(Intent(this, SMSDispatcherService::class.java))
        }
        catch(e : Exception) {
            Log.e(TAG, e.message)
        }



        val handler = Handler()
        var pre = readLogMessages()
        handler.postDelayed(object : Runnable {
            override fun run() { //your code
                handler.postDelayed(this, 2000)
                val text = readLogMessages()
                if(!pre.equals(text)) {
                    updateLogText(text)
                    pre = text
                }
            }
        }, 2000)
        //finish()

    }

    fun updateLogText(text : String) {

        runOnUiThread {
            run {
                Log.d(TAG, "doing shit on thread....")
                val tv = findViewById<TextView>(R.id.logBox)
                tv.setText(text)
            }
        }
    }


    private fun appendMessagesToFile( messages : List<SMSItem>) {

        // first read the file as json

        Log.d(TAG, "appending messages to file.....")

        val path = filesDir
        val file = File(path, filename)

        Log.d(TAG, file.absolutePath)

        var content : String? = null

        if(file.exists()) {
            val bytes = file.readBytes()
            content = String(bytes)
            Log.d(TAG,"content of pending messages is $content")
        }

        val new_list = if(content == null) {
            messages
        } else {
            val parsed = Klaxon().parseArray<SMSItem>(content)

            parsed.orEmpty() + messages
        }

        Log.d(TAG, "new list is $new_list")

        val res = Klaxon().toJsonString(new_list)
        file.writeBytes(res.toByteArray())

        Log.d(TAG, "DONE writing")

    }

    private fun readLogMessages() : String {

        val file = File(filesDir, "${logFileName}")
        var content = if(file.exists()) {
            val bytes = file.readBytes()
            String(bytes)
        } else {
            ""
        }

        return content

    }

    private fun clearLogMessages() {

        val file = File(filesDir, "${logFileName}")

        file.writeBytes("".toByteArray())
    }


    fun permissions() {

        if(ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            // no permission granted
            ActivityCompat.requestPermissions(this@MainActivity, arrayOf(android.Manifest.permission.SEND_SMS, android.Manifest.permission.READ_SMS, android.Manifest.permission.READ_PHONE_STATE), MY_PERMISSIONS_SEND_SMS)
        }
        else {
            Log.d(TAG, "Permissions are granted...")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when(requestCode) {
            MY_PERMISSIONS_SEND_SMS -> {
                if((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "PERMISSION GRANTED IN HERE");
                }
            }
        }
    }



}
