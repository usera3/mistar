package cn.qingliao.app

import android.content.Context
import android.media.*
import android.os.*

object AudioRoutes {
    val privateTypes=setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_BLE_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_USB_HEADSET)
    fun choose(types:Set<Int>,speaker:Boolean):Int? {
        if(speaker&&AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types)return AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        privateTypes.firstOrNull{it in types}?.let{return it}
        return AudioDeviceInfo.TYPE_BUILTIN_EARPIECE.takeIf{it in types}
    }
}

/** Focus, device routing and the bounded CPU lock share the lifetime of one accepted call. */
class CallAudio(private val context:Context,private val changed:()->Unit) {
    private val audio=context.getSystemService(AudioManager::class.java)
    private val oldMode=audio.mode
    private val oldSpeaker=audio.isSpeakerphoneOn
    private val oldDevice=if(Build.VERSION.SDK_INT>=31)audio.communicationDevice else null
    private var focus:AudioFocusRequest?=null
    private var focused=false
    private var routed=false
    private var closed=false
    private var registered=false
    private var startedSco=false
    var speaker=false
        private set
    val ready get()=focused&&routed&&!closed
    private var wake:PowerManager.WakeLock?=null
    val wakeHeld get()=wake?.isHeld==true
    private val renew=object:Runnable{override fun run(){if(!closed&&focus!=null){wake?.acquire(120000);Hub.main.postDelayed(this,60000)}}}
    private val devices=object:AudioDeviceCallback(){
        override fun onAudioDevicesAdded(added:Array<out AudioDeviceInfo>){if(!closed){if(added.any{it.type in AudioRoutes.privateTypes})speaker=false;route()}}
        override fun onAudioDevicesRemoved(removed:Array<out AudioDeviceInfo>){if(!closed)route()}
    }
    fun start(video:Boolean) {
        check(!closed)
        focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({value->
                if(!closed){focused=value==AudioManager.AUDIOFOCUS_GAIN;changed()}
            },Hub.main).setAcceptsDelayedFocusGain(false).build()
        focused=audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        check(focused){"当前音频被其他通话占用，请稍后重试"}
        audio.mode=AudioManager.MODE_IN_COMMUNICATION
        val choices=available();speaker=(video||choices.none{it.type==AudioDeviceInfo.TYPE_BUILTIN_EARPIECE})&&choices.none{it.type in AudioRoutes.privateTypes}
        route();audio.registerAudioDeviceCallback(devices,Hub.main);registered=true
        wake=context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"qingliao:active-call").apply{setReferenceCounted(false)}
        renew.run()
    }
    private fun available():List<AudioDeviceInfo> = if(Build.VERSION.SDK_INT>=31)audio.availableCommunicationDevices else audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
    private fun route() {
        if(closed)return
        val choices=available();val type=AudioRoutes.choose(choices.map{it.type}.toSet(),speaker)
        routed=if(Build.VERSION.SDK_INT>=31) {
            val device=choices.firstOrNull{it.type==type}
            if(device==null){audio.clearCommunicationDevice();false}else audio.setCommunicationDevice(device)
        } else {
            audio.isSpeakerphoneOn=type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            if(type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO&&!audio.isBluetoothScoOn){audio.startBluetoothSco();audio.isBluetoothScoOn=true;startedSco=true}
            type!=null
        }
        changed()
    }
    fun toggleSpeaker(){speaker=!speaker;route()}
    fun release() {
        if(closed)return;closed=true;focused=false;routed=false
        Hub.main.removeCallbacks(renew);wake?.let{if(it.isHeld)it.release()};wake=null
        if(registered)audio.unregisterAudioDeviceCallback(devices);registered=false
        focus?.let{audio.abandonAudioFocusRequest(it)};focus=null
        if(Build.VERSION.SDK_INT>=31){if(oldDevice!=null&&audio.availableCommunicationDevices.any{it.id==oldDevice.id})audio.setCommunicationDevice(oldDevice)else audio.clearCommunicationDevice()}
        else {if(startedSco){audio.stopBluetoothSco();audio.isBluetoothScoOn=false};audio.isSpeakerphoneOn=oldSpeaker}
        audio.mode=oldMode
    }
}
