package dev.saifmukhtar.antimatter.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.SecureRandom
import android.util.Base64
import java.util.UUID

data class Credentials(val url: String?, val clientId: String?, val clientSecret: String?, val token: String?, val pubKey: String?)

data class GatewayProfile(
    val id: String,
    val name: String,
    val credentials: Credentials
)

class UserPreferencesRepository(private val context: Context) {

    companion object {
        const val PROFILES_JSON = "profiles_json"
        const val ACTIVE_PROFILE_ID = "active_profile_id"
        const val DB_PASSPHRASE = "db_passphrase"
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val gson = Gson()

    private val _profilesFlow = MutableStateFlow<List<GatewayProfile>>(emptyList())
    val profilesFlow: StateFlow<List<GatewayProfile>> = _profilesFlow.asStateFlow()

    private val _savedCredentialsFlow = MutableStateFlow<Credentials>(Credentials(null, null, null, null, null))
    val savedCredentialsFlow: StateFlow<Credentials> = _savedCredentialsFlow.asStateFlow()

    private val _isLoadedFlow = MutableStateFlow(false)
    val isLoadedFlow: StateFlow<Boolean> = _isLoadedFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            loadProfiles()
            migrateLegacyCredentials()
            _isLoadedFlow.value = true
        }
    }

    private fun loadProfiles() {
        val json = securePrefs.getString(PROFILES_JSON, null)
        val profiles = if (json != null) {
            val type = object : TypeToken<List<GatewayProfile>>() {}.type
            gson.fromJson<List<GatewayProfile>>(json, type)
        } else {
            emptyList()
        }
        _profilesFlow.value = profiles

        val activeId = securePrefs.getString(ACTIVE_PROFILE_ID, null)
        val activeProfile = profiles.find { it.id == activeId }
        
        if (activeProfile != null) {
            _savedCredentialsFlow.value = activeProfile.credentials
        } else if (profiles.isNotEmpty()) {
            setActiveProfile(profiles.first().id)
        } else {
            _savedCredentialsFlow.value = Credentials(null, null, null, null, null)
        }
    }

    private fun migrateLegacyCredentials() {
        val url = securePrefs.getString("ws_url", null)
        if (url != null) {
            // Found legacy data, migrate it to a profile
            val clientId = securePrefs.getString("cf_client_id", null)
            val clientSecret = securePrefs.getString("cf_client_secret", null)
            val token = securePrefs.getString("auth_token", null)
            val pubKey = securePrefs.getString("pub_key", null)
            
            val credentials = Credentials(url, clientId, clientSecret, token, pubKey)
            saveProfile("Legacy Connection", credentials)
            
            // Clean up legacy keys
            securePrefs.edit()
                .remove("ws_url")
                .remove("cf_client_id")
                .remove("cf_client_secret")
                .remove("auth_token")
                .remove("pub_key")
                .apply()
        }
    }

    fun saveProfile(name: String, credentials: Credentials) {
        val currentProfiles = _profilesFlow.value.toMutableList()
        val id = UUID.randomUUID().toString()
        val newProfile = GatewayProfile(id, name, credentials)
        
        currentProfiles.add(newProfile)
        
        securePrefs.edit()
            .putString(PROFILES_JSON, gson.toJson(currentProfiles))
            .putString(ACTIVE_PROFILE_ID, id)
            .apply()
            
        _profilesFlow.value = currentProfiles
        _savedCredentialsFlow.value = credentials
    }

    fun setActiveProfile(id: String) {
        val profiles = _profilesFlow.value
        val profile = profiles.find { it.id == id }
        if (profile != null) {
            securePrefs.edit().putString(ACTIVE_PROFILE_ID, id).apply()
            _savedCredentialsFlow.value = profile.credentials
        }
    }

    fun deleteProfile(id: String) {
        val currentProfiles = _profilesFlow.value.toMutableList()
        currentProfiles.removeAll { it.id == id }
        
        val editor = securePrefs.edit().putString(PROFILES_JSON, gson.toJson(currentProfiles))
        
        val activeId = securePrefs.getString(ACTIVE_PROFILE_ID, null)
        if (activeId == id) {
            if (currentProfiles.isNotEmpty()) {
                val newActive = currentProfiles.first()
                editor.putString(ACTIVE_PROFILE_ID, newActive.id)
                _savedCredentialsFlow.value = newActive.credentials
            } else {
                editor.remove(ACTIVE_PROFILE_ID)
                _savedCredentialsFlow.value = Credentials(null, null, null, null, null)
            }
        }
        
        editor.apply()
        _profilesFlow.value = currentProfiles
    }
    
    // For backwards compatibility where legacy code called saveCredentials directly
    suspend fun saveCredentials(url: String, clientId: String, clientSecret: String, token: String?, pubKey: String?) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val creds = Credentials(url, clientId, clientSecret, token, pubKey)
            val currentProfiles = _profilesFlow.value.toMutableList()
            val existingIdx = currentProfiles.indexOfFirst { it.credentials.url == url }
            if (existingIdx >= 0) {
                val updated = currentProfiles[existingIdx].copy(credentials = creds)
                currentProfiles[existingIdx] = updated
                securePrefs.edit()
                    .putString(PROFILES_JSON, gson.toJson(currentProfiles))
                    .putString(ACTIVE_PROFILE_ID, updated.id)
                    .apply()
                _profilesFlow.value = currentProfiles
                _savedCredentialsFlow.value = creds
            } else {
                saveProfile("Scanned Connection", creds)
            }
        }
    }

    suspend fun clearCredentials() {
        val activeId = securePrefs.getString(ACTIVE_PROFILE_ID, null)
        if (activeId != null) {
            deleteProfile(activeId)
        }
    }

    fun getDatabasePassphrase(): ByteArray {
        var passphrase = securePrefs.getString(DB_PASSPHRASE, null)
        if (passphrase == null) {
            val secureRandom = SecureRandom()
            val key = ByteArray(32) // 256 bits
            secureRandom.nextBytes(key)
            passphrase = Base64.encodeToString(key, Base64.NO_WRAP)
            securePrefs.edit().putString(DB_PASSPHRASE, passphrase).apply()
        }
        return Base64.decode(passphrase, Base64.NO_WRAP)
    }
}
