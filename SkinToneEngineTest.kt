import com.xcology.livecamera.*
fun main() {
    val w=8;val h=8
    val darkSkin=(0xff shl 24) or (70 shl 16) or (48 shl 8) or 39
    val whiteCloth=(0xff shl 24) or (238 shl 16) or (238 shl 8) or 238
    val src=IntArray(w*h){ if (it%w<4) darkSkin else whiteCloth }
    val out=SkinToneEngine.transform(src,w,h,FloatArray(w*h){1f},w,h,ToneSettings(1f,0.5f))
    val darkBefore=(darkSkin shr 16) and 255
    val darkAfter=(out[0] shr 16) and 255
    check(darkAfter > darkBefore + 20){"Dark skin not lightened: $darkAfter"}
    check(out[w-1]==whiteCloth){"White clothing was unexpectedly changed"}
    val background=SkinToneEngine.transform(src,w,h,FloatArray(w*h){0f},w,h,ToneSettings(1f,0.5f))
    check(background.contentEquals(src)){"Background changed"}
    val disabled=SkinToneEngine.transform(src,w,h,FloatArray(w*h){1f},w,h,ToneSettings(0f,0.5f))
    check(disabled.contentEquals(src)){"Intensity zero changed pixels"}
    val warmSkin=(0xff shl 24) or (92 shl 16) or (60 shl 8) or 44
    val sem=IntArray(16){warmSkin}
    val skinMap=FloatArray(16){ if(it%4<2) 1f else 0f }
    val semOut=SkinToneEngine.transform(sem,4,4,skinMap,4,4,ToneSettings(1f,0.5f),semanticSkinMask=true)
    check(semOut[0] != warmSkin){"Skin class not transformed"}
    check(semOut[3] == warmSkin){"Non-skin semantic class incorrectly transformed"}
    println("SkinToneEngine tests passed: dark-skin brightening, white-clothes protection, background protection, intensity off")
}
// More extensive integration tests require Android camera frames and MediaPipe model on device.
