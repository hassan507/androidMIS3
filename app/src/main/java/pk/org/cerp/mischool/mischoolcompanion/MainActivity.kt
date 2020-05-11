package pk.org.cerp.mischool.mischoolcompanion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.Visibility
import android.os.Bundle
import android.os.Handler
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.DividerItemDecoration
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.beust.klaxon.Klaxon
import java.io.File


const val TAG = "MISchool-Companion"
const val MY_PERMISSIONS_SEND_SMS = 1
const val filename = "pending_messages.json"
const val logFileName = "logFile.txt"

private var list: RecyclerView? = null
private var country: ArrayList<String>? = null

class MainActivity : AppCompatActivity() {

    private var list: RecyclerView? = null
    private var recyclerAdapter: adapter? = null
    private var country: ArrayList<String>? = null

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
        val tv = findViewById<TextView>(R.id.logtext)
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
            Log.d("tryCc",""+parsed.messages.size);
            Log.e(TAG, parsed.messages.toString())
            //Log.d("tryMessage",parsed.messages.get(0));
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

            if(parsed.messages.size>0) {
                startService(Intent(this, SMSDispatcherService::class.java))
            }
        }
        catch(e : Exception) {
            Log.e(TAG, e.message)
        }


        list = findViewById(R.id.recyclerV) as RecyclerView
        val layoutManager = LinearLayoutManager(this)
        list!!.setLayoutManager(layoutManager)
        recyclerAdapter = adapter(this@MainActivity, SMSDispatcherService.list!!)
        list!!.addItemDecoration(DividerItemDecoration(list!!.getContext(), layoutManager.orientation))
        list!!.setAdapter(recyclerAdapter)

        val handler = Handler()
        var pre = readLogMessages()
        handler.postDelayed(object : Runnable {
            override fun run() { //your code
                recyclerAdapter!!.notifyDataSetChanged();
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
                val tv = findViewById<TextView>(R.id.logtext)
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

//    private fun processString(): String{
//        var msgtxt = readLogMessages()
//        var arr = msgtxt.split("\n")
//       // if(arr.co)
//    }
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

    private inner class adapter(internal var context: Context, internal var mData: List<SMSItem>) :
            RecyclerView.Adapter<adapter.myViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): adapter.myViewHolder {
            val view = LayoutInflater.from(context).inflate(R.layout.model, parent, false)
            return myViewHolder(view)
        }
        override fun onBindViewHolder(holder: adapter.myViewHolder, position: Int) {
            holder.country.text = mData[position].number+"\n"+mData[position].text+"\n"+mData[position].status

            if(!mData[position].status.equals("SENT") && !mData[position].status.equals("PENDING")){
                holder.resend.visibility =  View.VISIBLE;
            }else{
                holder.resend.visibility =  View.GONE
            }
            holder.resend.setOnClickListener(View.OnClickListener {
                Toast.makeText(context,"retrying",Toast.LENGTH_LONG).show();
                val msgs = arrayListOf<SMSItem>();
                msgs.add(mData[position]);
                appendMessagesToFile(msgs)
                SMSDispatcherService.list.removeAt(mData[position].index!!);
                startService(Intent(this@MainActivity, SMSDispatcherService::class.java))
            })

        }
        override fun getItemCount(): Int {
            return mData.size
        }
        inner class myViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            internal var country: TextView
            internal var resend: Button
            init {
                country = itemView.findViewById(R.id.textView)
                resend = itemView.findViewById(R.id.resendButton)
            }
        }
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
