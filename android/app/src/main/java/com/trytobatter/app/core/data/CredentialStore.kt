package com.trytobatter.app.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class PairingCredentials(val address: String, val serverId: String, val serverName: String, val deviceId: String, val token: String)

/** Credentials stay out of Room snapshots, exported backups, and server logs. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("batter-lan-credentials", Context.MODE_PRIVATE)
    private val alias = "batter-lan-aes-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): PairingCredentials? {
        val encrypted = preferences.getString("payload", null) ?: return null
        val iv = preferences.getString("iv", null) ?: error("设备凭证不完整，请重新配对")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val j = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
        return PairingCredentials(j.getString("address"), j.getString("serverId"), j.getString("serverName"), j.getString("deviceId"), j.getString("token"))
    }
    fun save(c: PairingCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = JSONObject().put("address", c.address).put("serverId", c.serverId).put("serverName", c.serverName).put("deviceId", c.deviceId).put("token", c.token).toString()
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP)).putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit()) { "设备凭证保存失败" }
    }
    fun clear() { check(preferences.edit().clear().commit()) { "断开配对失败，请重试" } }
}
