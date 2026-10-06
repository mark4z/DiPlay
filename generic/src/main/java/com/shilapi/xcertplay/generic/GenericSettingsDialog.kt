package com.shilapi.xcertplay.generic

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

internal object GenericSettingsDialog {
    fun show(activity: Activity, value: GenericSettings, save: (GenericSettings) -> Unit, dismissed: () -> Unit): AlertDialog {
        val padding = (16 * activity.resources.displayMetrics.density).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun note(text: String, target: LinearLayout = body) = TextView(activity).apply {
            this.text = text
            setPadding(0, padding / 2, 0, padding / 3)
            target.addView(this)
        }
        fun group() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        fun field(title: String, initial: String, target: LinearLayout = body, secret: Boolean = false): EditText {
            note(title, target)
            return EditText(activity).apply {
                setText(initial); setSingleLine(); isSaveEnabled = false
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                target.addView(this)
            }
        }
        fun chooser(title: String, names: List<String>, selected: Int, target: LinearLayout = body): Spinner {
            note(title, target)
            return Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, names)
                setSelection(selected); target.addView(this)
            }
        }
        note("Keep this app visible while connected. Leaving it releases USB, Wi-Fi and microphone; returning reconnects. Local source builds need an explicitly provisioned identity or an authentication device/server.")
        val transport = chooser("Connection", listOf("Wired USB", "Wireless"), if (value.wireless) 1 else 0)
        val network = group()
        val modes = WirelessHotspotMode.entries
        val mode = chooser("Wireless network", listOf("Wi-Fi Direct (P2P)", "Android local-only hotspot", "Manual hotspot", "Same LAN (existing Wi-Fi)"), modes.indexOf(value.hotspotMode), network)
        note("Pair the iPhone in Android Bluetooth settings first. Manual mode uses an already enabled hotspot. Same LAN uses your existing Android Wi-Fi connection; enter that network's name/password. Neither mode changes Android settings for you.", network)
        val ssid = field("Manual / Same LAN network name", value.ssid, network)
        val passphrase = field("Network password (blank for open)", value.passphrase, network, true)
        val bluetooth = field("Paired iPhone Bluetooth address (optional)", value.bluetoothAddress, network)
        for ((title, action) in listOf("Android Bluetooth settings" to Settings.ACTION_BLUETOOTH_SETTINGS, "Android Wi-Fi settings" to Settings.ACTION_WIFI_SETTINGS)) {
            network.addView(Button(activity).apply {
                text = title
                setOnClickListener { runCatching { activity.startActivity(Intent(action)) } }
            })
        }
        val targets = MfiTarget.entries
        val target = chooser("Authentication", listOf("Local provisioned identity", "USB / CH341 coprocessor", "Linux I2C coprocessor", "Remote authentication server"), targets.indexOf(value.mfiTarget))
        val usb = group()
        val vendor = field("CH341 USB vendor ID (hex)", value.usbVendorId, usb)
        val product = field("CH341 USB product ID (hex)", value.usbProductId, usb)
        val i2c = group()
        note("Requires an existing OS-granted device permission. The app never roots the device or grants itself access.", i2c)
        val path = field("Linux I2C path", value.i2cPath, i2c)
        val remote = group()
        note("The chosen server receives authentication challenges. A token requires HTTPS. Values stay in this app's private, backup-excluded storage.", remote)
        val server = field("Server URL", value.remoteServer, remote)
        val token = field("Bearer token (optional)", value.remoteToken, remote, true)
        val microphone = CheckBox(activity).apply { text = "Microphone for Siri and calls"; isChecked = value.microphone; body.addView(this) }
        val width = field("Physical display width in mm (40–1000)", value.widthMillimeters.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        note("Only the main CarPlay screen is advertised. Optional parked-video playback stays disabled because there is no trusted gear signal.")
        val error = note("")
        fun refresh() {
            network.visibility = if (transport.selectedItemPosition == 1) View.VISIBLE else View.GONE
            usb.visibility = if (targets[target.selectedItemPosition] == MfiTarget.USB_CH341) View.VISIBLE else View.GONE
            i2c.visibility = if (targets[target.selectedItemPosition] == MfiTarget.I2C) View.VISIBLE else View.GONE
            remote.visibility = if (targets[target.selectedItemPosition] == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        }
        val change = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refresh() }
        }
        transport.onItemSelectedListener = change; target.onItemSelectedListener = change
        val dialog = AlertDialog.Builder(activity).setTitle("Generic connection settings")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnDismissListener { dismissed() }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            refresh()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val next = GenericSettings(
                    wireless = transport.selectedItemPosition == 1,
                    hotspotMode = modes[mode.selectedItemPosition],
                    ssid = ssid.text.toString(), passphrase = passphrase.text.toString(),
                    bluetoothAddress = bluetooth.text.toString().trim(),
                    mfiTarget = targets[target.selectedItemPosition],
                    usbVendorId = vendor.text.toString().trim(), usbProductId = product.text.toString().trim(),
                    i2cPath = path.text.toString().trim(), remoteServer = server.text.toString().trim(),
                    remoteToken = token.text.toString(), microphone = microphone.isChecked,
                    widthMillimeters = width.text.toString().toIntOrNull() ?: 0,
                )
                val problem = next.validationError()
                if (problem != null) error.text = problem else { save(next); dialog.dismiss() }
            }
        }
        dialog.show()
        return dialog
    }
}
