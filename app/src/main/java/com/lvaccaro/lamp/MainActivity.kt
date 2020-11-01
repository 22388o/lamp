package com.lvaccaro.lamp

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PersistableBundle
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.lvaccaro.lamp.activities.*
import com.lvaccaro.lamp.adapters.Balance
import com.lvaccaro.lamp.adapters.BalanceAdapter
import com.lvaccaro.lamp.fragments.HistoryFragment
import com.lvaccaro.lamp.fragments.InvoiceBuildFragment
import com.lvaccaro.lamp.fragments.WithdrawFragment
import com.lvaccaro.lamp.handlers.BrokenStatus
import com.lvaccaro.lamp.handlers.NewBlockHandler
import com.lvaccaro.lamp.handlers.NewChannelPayment
import com.lvaccaro.lamp.handlers.ShutdownNode
import com.lvaccaro.lamp.services.LightningService
import com.lvaccaro.lamp.services.TorService
import com.lvaccaro.lamp.utils.Archive
import com.lvaccaro.lamp.utils.SimulatorPlugin
import com.lvaccaro.lamp.utils.UI
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main_off.*
import kotlinx.android.synthetic.main.content_main_on.*
import org.jetbrains.anko.doAsync
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.logging.Logger


class MainActivity : UriResultActivity() {

    private val REQUEST_SCAN = 102
    private val REQUEST_FUNDCHANNEL = 103
    private val WRITE_REQUEST_CODE = 101
    private val log = Logger.getLogger(MainActivity::class.java.name)
    private var downloadID = 0L
    private var downloadCertID = 0L
    private var blockcount = 0
    private lateinit var downloadmanager: DownloadManager
    private var isFirstStart = true

    private fun dir(): File {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        powerImageView.setOnClickListener { this.onPowerClick() }
        arrowImageView.setOnClickListener { this.onHistoryClick() }

        //restoreBalanceValue(savedInstanceState)

        registerLocalReceiver()

        /*val addressTextView = findViewById<TextView>(R.id.textViewQr)
        addressTextView.setOnClickListener {
            UI.copyToClipboard(
                this,
                "address",
                addressTextView.text.toString()
            )
        }*/


        floatingActionButton.setOnClickListener {
            val intent = Intent(this, ScanActivity::class.java)
            startActivityForResult(intent, REQUEST_SCAN)
        }

        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            val itemDecoration = DividerItemDecoration(context, DividerItemDecoration.VERTICAL)
            itemDecoration.setDrawable(getDrawable(R.drawable.divider)!!)
            addItemDecoration(itemDecoration)
        }

        downloadmanager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        registerReceiver(onDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    WRITE_REQUEST_CODE
                )
            }
        }

        receiveButton.setOnClickListener {
            val bottomSheetDialog =
                InvoiceBuildFragment()
            bottomSheetDialog.show(supportFragmentManager, "Custom Bottom Sheet")

        }

        sendButton.setOnClickListener {
            startActivity(Intent(this, PayViewActivity::class.java))
        }

        if (Intent.ACTION_VIEW == intent.action) {
            if (arrayListOf<String>("bitcoin", "lightning").contains(intent.data.scheme)) {
                val text = intent.data.toString().split(":").last()
                parse(text)
            }
        }

        // Check lightning_ndk release version
        val release = getPreferences(Context.MODE_PRIVATE).getString("RELEASE", Archive.RELEASE)
        if (release != Archive.RELEASE) {
            AlertDialog.Builder(this)
                .setTitle(R.string.id_update)
                .setMessage("New lightning_ndk version is available: ${Archive.RELEASE}. Make a backup from Settings. Tap Update to start download.")
                .setPositiveButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton(R.string.id_update) { _, _ ->
                    // Stop node
                    stop()
                    // Delete previous binaries
                    val dir = File(rootDir(), "")
                    val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
                    Archive.delete(downloadDir)
                    Archive.deleteUncompressed(dir)
                    // Download new binaries release
                    statusText.text =
                        "Downloading..."
                    powerImageView.animating()
                    download()
                }
                .show()
        }

        powerOff()
    }

    override fun onResume() {
        super.onResume()

        if (!File(rootDir(), "cli/lightning-cli").exists()) {
            statusText.text =
                "Rub the lamp to download ${Archive.RELEASE} binaries."
            return
        }

        if (!isLightningRunning()) {
            statusText.text =
                "Offline. Rub the lamp to start."

            val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            if (isFirstStart && sharedPref.getBoolean("autostart", true))
                start()
            isFirstStart = false
            return
        }

        contentMainOn.visibility = View.VISIBLE
        contentMainOff.visibility = View.GONE
        doAsync {
            getInfo()
            runIntent(NewChannelPayment.NOTIFICATION)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadReceiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>, grantResults: IntArray
    ) {
        when (requestCode) {
            WRITE_REQUEST_CODE -> {
                if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "Permission has been denied by user")
                } else {
                    Log.i(TAG, "Permission has been granted by user")
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        if (!isLightningRunning()) {
            menu?.apply {
                removeItem(R.id.action_console)
                removeItem(R.id.action_channels)
                removeItem(R.id.action_withdraw)
                removeItem(R.id.action_new_address)
                removeItem(R.id.action_stop)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_stop -> {
                doAsync { stop() }
                true
            }
            R.id.action_settings -> {
                startActivityForResult(Intent(this, SettingsActivity::class.java), 100)
                true
            }
            R.id.action_log -> {
                startActivityForResult(Intent(this, LogActivity::class.java), 100)
                true
            }
            R.id.action_console -> {
                startActivityForResult(Intent(this, ConsoleActivity::class.java), 100)
                true
            }
            R.id.action_channels -> {
                startActivityForResult(Intent(this, ChannelsActivity::class.java), 100)
                true
            }
            R.id.action_withdraw -> {
                val bottomSheetDialog =
                    WithdrawFragment()
                bottomSheetDialog.show(supportFragmentManager, "WithdrawFragment")
                true
            }
            R.id.action_new_address -> {
                doAsync { generateNewAddress() }
                true
            }
            R.id.action_paste -> {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val item = clip?.getItemAt(0)
                val text = item?.text.toString()
                doAsync { parse(text) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_SCAN && resultCode == Activity.RESULT_OK) {
            val result = data?.getStringExtra("text")
            if (result != null) {
                doAsync { parse(result) }
                return
            }
            UI.snackBar(this, "Scan failed")
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onSaveInstanceState(outState: Bundle?, outPersistentState: PersistableBundle?) {
        super.onSaveInstanceState(outState, outPersistentState)
        Log.d(TAG, "onSaveInstanceState called")
        /*val balanceOffChain = viewOnRunning.findViewById<TextView>(R.id.off_chain).text.toString()
        val balanceOnChain = viewOnRunning.findViewById<TextView>(R.id.on_chain).text.toString()
        val balanceOurChain =
            viewOnRunning.findViewById<TextView>(R.id.value_balance_text).text.toString()
        outState?.putString(LampKeys.OFF_CHAIN_BALANCE, balanceOffChain)
        outState?.putString(LampKeys.ON_CHAIN_BALANCE, balanceOnChain)
        outState?.putString(LampKeys.OUR_CHAIN_BALANCE, balanceOurChain)*/
    }

    //FIXME(vincenzopalazzo) we don't need this with the actual implementation of UI
    //because inside the one create I notify the receive to update the fund label
    //but I add this in cases we will make change
    private fun restoreBalanceValue(savedInstanceState: Bundle?) {
        /*viewOnRunning.findViewById<TextView>(R.id.off_chain).text =
            savedInstanceState?.getString(LampKeys.OFF_CHAIN_BALANCE) ?: "Unavaible"

        viewOnRunning.findViewById<TextView>(R.id.on_chain).text =
            savedInstanceState?.getString(LampKeys.ON_CHAIN_BALANCE) ?: "Unavaible"

        viewOnRunning.findViewById<TextView>(R.id.value_balance_text).text =
            savedInstanceState?.getString(LampKeys.OUR_CHAIN_BALANCE) ?: "Unavaible"*/
    }

    fun updateBalanceView(context: Context?, intent: Intent?) {
        if (!isLightningRunning()) return
        val listFunds = cli.exec(context!!, arrayOf("listfunds"), true).toJSONObject()
        val listpeers = cli.exec(context!!, arrayOf("listpeers"), true).toJSONObject()
        val fundInChannels: JSONObject = SimulatorPlugin.fundsInChannel(listpeers) as JSONObject

        var onChainFunds = 0
        val outputs: JSONArray = listFunds["outputs"] as JSONArray
        for (i in 0 until outputs.length()) {
            val output = outputs.getJSONObject(i)
            onChainFunds += output["value"] as Int
        }
        recyclerView.adapter = BalanceAdapter(
            arrayListOf(
                Balance("Lightning balance", "", "${SimulatorPlugin.offchain(listFunds)} msat"),
                Balance("Spendable in channels", "", "${fundInChannels["to_us"].toString()} msat"),
                Balance("Bitcoin on chain", "${outputs.length()} Transactions", "${onChainFunds} sat")
            ), null
        )
    }


    private fun isServiceRunning(name: String): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (name.equals(service.service.className)) {
                return true
            }
        }
        return false
    }

    private fun isLightningRunning(): Boolean {
        return isServiceRunning(LightningService::class.java.canonicalName)
    }

    private fun isTorRunning(): Boolean {
        return isServiceRunning(TorService::class.java.canonicalName)
    }

    private fun onHistoryClick() {
        HistoryFragment()
            .show(getSupportFragmentManager(), "History dialog")
    }

    private fun onPowerClick() {
        if (powerImageView.isAnimating()) {
            return
        }
        if (powerImageView.isOn()) {
            // turn off
            doAsync {
                stop()
            }
            return
        }

        val isLightningReady = File(rootDir(), "cli/lightning-cli").exists()
        if (isLightningReady) {
            // turn on
            start()
            return
        }
        val tarFile = File(dir(), Archive.tarFilename())
        if (tarFile.exists()) {
            // Uncompress package
            statusText.text =
                "Package already downloaded. Uncompressing..."
            powerImageView.animating()
            doAsync {
                Archive.uncompressXZ(tarFile, rootDir())
                runOnUiThread {
                    powerOff()
                }
            }
        } else {
            statusText.text =
                "Downloading..."
            powerImageView.animating()
            download()
        }
    }

    private fun powerOff() {
        contentMainOn.visibility = View.GONE
        contentMainOff.visibility = View.VISIBLE
        val release = getPreferences(Context.MODE_PRIVATE).getString("RELEASE", "")
        versionText.text = "Version: ${BuildConfig.VERSION_NAME} - ${release}"
        statusText.text = "Offline. Rub the lamp to turn on."
        powerImageView.off()
        invalidateOptionsMenu()
    }

    private fun powerOn() {
        contentMainOn.visibility = View.VISIBLE
        contentMainOff.visibility = View.GONE
        powerImageView.on()
        invalidateOptionsMenu()
        runIntent(NewChannelPayment.NOTIFICATION)
    }

    private fun getInfo() {
        try {
            val resChainInfo =
                LightningCli().exec(this@MainActivity, arrayOf("getchaininfo"), true).toJSONObject()
            blockcount = resChainInfo["blockcount"] as Int

            val res =
                LightningCli().exec(this@MainActivity, arrayOf("getinfo"), true).toJSONObject()
            val id = res["id"] as String
            val addresses = res["address"] as JSONArray
            // the node has an address? if not hide the UI node info
            var public = addresses.length() != 0
            val alias = res["alias"] as String
            var txt = ""
            if (public) {
                val address = addresses[0] as JSONObject
                txt = id + "@" + address.getString("address")
            }
            val blockheight = res["blockheight"] as Int

            runOnUiThread {
                title = alias
                powerImageView.on()
                /*if (public) {
                    findViewById<ImageView>(R.id.arrowImageView).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.textViewQr).apply {
                        text = txt
                        visibility = View.VISIBLE
                    }
                }*/
                val delta = blockcount - blockheight
                syncText.text = if (delta > 0) "Syncing blocks -${delta}" else ""
            }

            // Generate qrcode
            /*if (public) {
                val qrcode = getQrCode(txt)
                runOnUiThread {
                    findViewById<ImageView>(R.id.qrcodeImageView).apply {
                        visibility = View.VISIBLE
                        setImageBitmap(qrcode)
                    }
                }
            }*/
        } catch (e: Exception) {
            log.info("---" + e.localizedMessage + "---")
            runOnUiThread {
                stopLightningService()
                stopTorService()
                powerOff()
                UI.snackBar(this, e.localizedMessage)
            }
        }
    }

    fun getQrCode(text: String): Bitmap {
        val SCALE = 16
        try {
            val matrix = Encoder.encode(text, ErrorCorrectionLevel.M).matrix
            val height = matrix.height * SCALE
            val width = matrix.width * SCALE
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width)
                for (y in 0 until height) {
                    val point = matrix.get(x / SCALE, y / SCALE).toInt()
                    bitmap.setPixel(x, y, if (point == 0x01) Color.BLACK else 0)
                }
            return bitmap
        } catch (e: WriterException) {
            throw RuntimeException(e)
        }
    }

    private val onDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (downloadID != id)
                return

            runOnUiThread {
                getPreferences(Context.MODE_PRIVATE).edit().putString("RELEASE", Archive.RELEASE).apply()
                statusText.text =
                    "Download Completed. Uncompressing..."
            }
            val tarFile = File(dir(), Archive.tarFilename())
            doAsync {
                Archive.uncompressXZ(tarFile, rootDir())
                runOnUiThread { powerOff() }
            }
        }
    }

    private fun download() {
        // Download bitcoin_ndk package
        val tarFile = File(dir(), Archive.tarFilename())
        val request = DownloadManager.Request(Uri.parse(Archive.url()))
        request.setTitle("lightning")
        request.setDescription(getString(R.string.id_downloading))
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        request.setDestinationUri(tarFile.toUri())
        downloadID = downloadmanager.enqueue(request)

        // Download CA certificates
        val cacert = "https://curl.haxx.se/ca/cacert.pem"
        val fileCert = File(dir(), "cacert.pem")
        val requestCert = DownloadManager.Request(Uri.parse(cacert))
        requestCert.setTitle("CA certificates")
        requestCert.setDescription(getString(R.string.id_downloading))
        requestCert.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        requestCert.setDestinationUri(fileCert.toUri())
        downloadCertID = downloadmanager.enqueue(requestCert)
    }

    private fun waitTorBootstrap(): Boolean {
        val logFile = File(rootDir(), "tor.log")
        for (i in 0..10) {
            try {
                if (logFile.readText().contains("100%"))
                    return true
            } catch (err: Exception) {
            }
            Thread.sleep(2000)
        }
        return false
    }

    private fun waitLightningBootstrap(): Boolean {
        val logFile = File(rootDir(), "lightningd.log")
        for (i in 0..10) {
            try {
                if (logFile.readText().contains("lightningd: Server started with public key"))
                    return true
            } catch (err: Exception) {
                Log.d(TAG, err.localizedMessage)
            }
            Thread.sleep(2000)
        }
        return false
    }

    private fun start() {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val rpcuser = sharedPref.getString("bitcoin-rpcuser", "").toString()
        val rpcpassword = sharedPref.getString("bitcoin-rpcpassword", "").toString()
        val esplora = sharedPref.getBoolean("enabled-esplora", true)
        if (!esplora && (rpcuser === "" || rpcpassword === "")) {
            AlertDialog.Builder(this)
                .setTitle("warning")
                .setMessage("Go to Settings to set lightningd options before start")
                .setNegativeButton("cancel") { dialog, which -> }
                .setPositiveButton("settings") { dialog, which ->
                    startActivityForResult(Intent(this, SettingsActivity::class.java), 100)
                }
                .show()
            return
        }

        val torEnabled = sharedPref.getBoolean("enabled-tor", true)

        // clear log files
        val torLogFile = File(rootDir(), "tor.log")
        val lightningLogFile = File(rootDir(), "lightningd.log")
        torLogFile.delete()
        lightningLogFile.delete()

        powerImageView.animating()
        doAsync {
            if (torEnabled) {
                // start service on main thread
                runOnUiThread {
                    statusText.text =
                        "Starting tor..."
                    startTor()
                }
                // wait tor to be bootstrapped
                if (!waitTorBootstrap()) {
                    runOnUiThread {
                        Log.d(TAG, "******** Tor run failed ********")
                        stopTorService()
                        powerOff()
                        UI.snackBar(this@MainActivity, "Tor start failed")
                    }
                    return@doAsync
                }
            }
            // start service on main thread
            runOnUiThread {
                statusText.text =
                    "Starting lightning..."
                startLightning()
            }
            // wait lightning to be bootstrapped
            if (!waitLightningBootstrap()) {
                showMessageOnToast("Lightning start failed")
                runOnUiThread {
                    Log.d(TAG, "******** Lightning run failed ********")
                    stopLightningService()
                    powerOff()
                }
                return@doAsync
            }
            try {
                getInfo()
                runOnUiThread { powerOn() }
            } catch (e: Exception) {
                log.info("---" + e.localizedMessage + "---")
                stop()
                runOnUiThread { UI.snackBar(this@MainActivity, e.localizedMessage) }
            }
        }
    }

    private fun stopTorService() {
        stopService(Intent(this, TorService::class.java))
    }

    private fun stopLightningService() {
        stopService(Intent(this, LightningService::class.java))
    }

    private fun startTor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, TorService::class.java))
        } else {
            startService(Intent(this, TorService::class.java))
        }
    }

    private fun startLightning() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, LightningService::class.java))
        } else {
            startService(Intent(this, LightningService::class.java))
        }
    }

    private fun stop() {
        log.info("---onStop---")
        try {
            val res = LightningCli().exec(this, arrayOf("stop"))
            log.info("---" + res.toString() + "---")
        } catch (e: Exception) {
            runOnUiThread { UI.snackBar(this, "Error: ${e.localizedMessage}") }
            log.warning(e.localizedMessage)
            e.printStackTrace()
        }
        runOnUiThread {
            stopLightningService()
            stopTorService()
            powerOff()
        }
    }

    private fun generateNewAddress() {
        val res = cli.exec(
            this@MainActivity,
            arrayOf("newaddr"),
            true
        ).toJSONObject()
        runOnUiThread {
            val textView = TextView(this)
            val qr = ImageView(this)
            val address = res["address"].toString()
            textView.text = address
            qr.setImageBitmap(getQrCode(address))
            val layoutParams = LinearLayout.LayoutParams(300, 300)
            layoutParams.gravity = Gravity.CENTER_HORIZONTAL
            qr.layoutParams = layoutParams
            val container = LinearLayout(this)
            container.orientation = LinearLayout.VERTICAL
            container.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            container.setPadding(16, 16, 16, 16)
            container.addView(textView)
            container.addView(qr)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("New address")
                .setView(container)
                .setPositiveButton("clipboard") { dialog, which ->
                    UI.copyToClipboard(this, "address", address)
                }.setNegativeButton("cancel") { dialog, which -> }
                .setCancelable(false)
                .show()
        }
    }

    private fun registerLocalReceiver() {
        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        val intentFilter = IntentFilter()
        intentFilter.addAction(ShutdownNode.NOTIFICATION)
        intentFilter.addAction(NewChannelPayment.NOTIFICATION)
        intentFilter.addAction(NewBlockHandler.NOTIFICATION)
        intentFilter.addAction(BrokenStatus.NOTIFICATION)
        localBroadcastManager.registerReceiver(notificationReceiver, intentFilter)
    }

    private fun runIntent(key: String) {
        val intent = Intent(key)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        // I can create a mediator that I can use to call all method inside the
        //lightning-cli and return a json if the answer i ok or I throw an execeptions

        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "onReceive action ${intent?.action}")
            when (intent?.action) {
                NewChannelPayment.NOTIFICATION -> runOnUiThread {
                    updateBalanceView(context, intent)
                }
                ShutdownNode.NOTIFICATION ->  runOnUiThread {
                    powerOff()
                }
                NewBlockHandler.NOTIFICATION ->  runOnUiThread {
                    val blockheight = intent.getIntExtra("height", 0)
                    val delta = blockcount - blockheight
                    statusText.text = if (delta > 0) "Syncing blocks -${delta}" else ""
                }
                BrokenStatus.NOTIFICATION -> runOnUiThread{
                    val message = intent.getStringExtra("message")
                    UI.snackBar(this@MainActivity, message)
                    powerOff()
                    stopTorService()
                }
            }
        }
    }
}
