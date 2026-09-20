package com.zenith.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.Arrays

/**
 * ZenithSecureVault: Hardware-Keystore Backed Credential Vault & Secure Input Injector.
 *
 * Utilizes Android Jetpack Security (Crypto) and hardware-backed Android KeyStore (AES-256 GCM)
 * to encrypt, store, and inject API keys, tokens, and passwords directly into accessibility
 * input fields without exposing plain text over WebSocket bridges or UI buffers.
 */
class ZenithSecureVault(private val context: Context) {

    companion object {
        private const val TAG = "ZenithSecureVault"
        private const val VAULT_PREFS_NAME = "zenith_secure_vault_prefs"

        @Volatile
        private var instance: ZenithSecureVault? = null

        fun getInstance(context: Context): ZenithSecureVault {
            return instance ?: synchronized(this) {
                instance ?: ZenithSecureVault(context.applicationContext).also { instance = it }
            }
        }
    }

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                VAULT_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences with hardware MasterKey: ${e.message}", e)
            context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Stores an encrypted credential inside the hardware-backed keystore vault.
     */
    fun storeCredential(key: String, secret: String) {
        if (key.isBlank()) return
        securePrefs.edit().putString(key, secret).apply()
        Log.i(TAG, "Credential securely stored under key '$key' [AES-256 GCM]")
    }

    /**
     * Retrieves and decrypts the credential corresponding to [key].
     */
    fun retrieveCredential(key: String): String? {
        if (key.isBlank()) return null
        return securePrefs.getString(key, null)
    }

    /**
     * Deletes a credential from the secure vault.
     */
    fun deleteCredential(key: String) {
        if (key.isBlank()) return
        securePrefs.edit().remove(key).apply()
        Log.i(TAG, "Credential removed for key '$key'")
    }

    /**
     * Lists all stored credential keys (without decrypting values).
     */
    fun listCredentialKeys(): Set<String> {
        return securePrefs.all.keys
    }

    /**
     * Injects a decrypted credential directly into an active [AccessibilityNodeInfo] input field.
     * Memory buffers containing the secret are cleared immediately following execution.
     *
     * @param targetNodeId The view ID or resource identifier of the target input node.
     * @param credentialKey The key of the secret stored in [ZenithSecureVault].
     * @return True if injection succeeded, false otherwise.
     */
    fun injectCredentialToField(targetNodeId: String, credentialKey: String): Boolean {
        val rawSecret = retrieveCredential(credentialKey) ?: run {
            Log.w(TAG, "Cannot inject secret: Key '$credentialKey' not found in Secure Vault.")
            return false
        }

        val secretCharArray = rawSecret.toCharArray()
        try {
            val a11yService = ZenithAccessibilityService.instance ?: run {
                Log.w(TAG, "Cannot inject secret: ZenithAccessibilityService is not connected.")
                return false
            }

            val rootNode = a11yService.rootInActiveWindow ?: run {
                Log.w(TAG, "Cannot inject secret: rootInActiveWindow is null.")
                return false
            }

            // Locate target node by view ID or focused input field
            val targetNode = findTargetInputNode(rootNode, targetNodeId) ?: run {
                Log.w(TAG, "Target input node '$targetNodeId' could not be located in active window.")
                return false
            }

            val secretCharSequence = String(secretCharArray)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    secretCharSequence
                )
            }

            val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "Secure secret injection for key '$credentialKey' into '$targetNodeId' (Success: $success)")
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting credential to field: ${e.message}", e)
            return false
        } finally {
            // Overwrite and wipe decrypted secret from memory immediately
            Arrays.fill(secretCharArray, '\u0000')
        }
    }

    private fun findTargetInputNode(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        // 1. Try finding by View Resource ID
        if (targetId.isNotBlank()) {
            val matchedById = root.findAccessibilityNodeInfosByViewId(targetId)
            if (matchedById != null && matchedById.isNotEmpty()) {
                val inputNode = matchedById.firstOrNull { it.isEditable || it.isFocusable }
                if (inputNode != null) return inputNode
                return matchedById.first()
            }
        }

        // 2. Try finding focused input
        val focusedInput = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null) return focusedInput

        // 3. Recursive tree search matching ID or editable input
        return searchTreeForEditable(root, targetId)
    }

    private fun searchTreeForEditable(node: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName
        if (viewId != null && targetId.isNotBlank() && viewId.contains(targetId, ignoreCase = true)) {
            return node
        }

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        if (targetId.isNotBlank() && (text.contains(targetId, ignoreCase = true) || contentDesc.contains(targetId, ignoreCase = true))) {
            return node
        }

        if (node.isEditable && targetId.isBlank()) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchTreeForEditable(child, targetId)
            if (found != null) return found
        }

        return null
    }
}
