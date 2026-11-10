package com.campusmarket.app.platform

import com.campusmarket.app.data.SecretStorage
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Keychain-backed [SecretStorage]: one `kSecClassGenericPassword` item, readable after the first unlock, never
 * restored onto another device (`...ThisDeviceOnly`) and never synced to iCloud (`kSecAttrSynchronizable = false`).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainSecretStorage(private val service: String, private val account: String) : SecretStorage {
    override val isSecure: Boolean get() = true

    override fun read(): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery { query ->
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(query, result.ptr)
        }
        when (status) {
            errSecItemNotFound -> null
            errSecSuccess -> {
                val data = CFBridgingRelease(result.value) as? NSData ?: error("Keychain returned no data")
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    ?: error("Keychain item is not valid UTF-8")
            }
            else -> error("Keychain read failed: OSStatus $status")
        }
    }

    override fun write(value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: error("cannot encode secret")
        val dataRef = CFBridgingRetain(data)
        try {
            // Update the existing item in place; add it when there is none yet.
            val updateStatus = withQuery { query ->
                val attrs = newDictionary()
                try {
                    CFDictionaryAddValue(attrs, kSecValueData, dataRef)
                    SecItemUpdate(query, attrs)
                } finally {
                    CFRelease(attrs)
                }
            }
            when (updateStatus) {
                errSecSuccess -> return
                errSecItemNotFound -> {
                    val addStatus = withQuery { query ->
                        CFDictionaryAddValue(query, kSecValueData, dataRef)
                        CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                        SecItemAdd(query, null)
                    }
                    check(addStatus == errSecSuccess) { "Keychain add failed: OSStatus $addStatus" }
                }
                else -> error("Keychain update failed: OSStatus $updateStatus")
            }
        } finally {
            CFRelease(dataRef)
        }
    }

    override fun delete() {
        val status = withQuery { query -> SecItemDelete(query) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed: OSStatus $status" }
    }

    /** Runs [block] with the identifying query (class, service, account, not synchronizable); releases everything after. */
    private fun <T> withQuery(block: (CFMutableDictionaryRef?) -> T): T {
        val query = newDictionary()
        val serviceRef: COpaquePointer? = CFBridgingRetain(service)
        val accountRef: COpaquePointer? = CFBridgingRetain(account)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            CFDictionaryAddValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            return block(query)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(query)
        }
    }

    private fun newDictionary(): CFMutableDictionaryRef? = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    )
}
