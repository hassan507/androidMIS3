package pk.org.cerp.mischool.mischoolcompanion

import android.app.Application
import android.content.Intent
import android.util.Log


class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG,"a     inside app");
        startService(Intent(this, SMSDispatcherService::class.java))
        //JobManager.create(this).addJobCreator(SMSDispatcher())
    }
}