/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.contact

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.RawContacts
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.activities.main.contact.viewmodels.NumberOrAddressEditorViewModel
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils
import org.linphone.utils.PermissionHelper

class NativeContactEditor(
    val contact: NativeContact,
    private var syncAccountName: String? = null,
    private var syncAccountType: String? = null
) {
    companion object {
        fun createAndroidContact(accountName: String? = null, accountType: String? = null): Long {
            val values = ContentValues()
            values.put(RawContacts.ACCOUNT_NAME, accountName)
            values.put(RawContacts.ACCOUNT_TYPE, accountType)

            val rawContactUri = coreContext.context.contentResolver
                .insert(RawContacts.CONTENT_URI, values)
            return ContentUris.parseId(rawContactUri)
        }
    }

    private val changes = arrayListOf<ContentProviderOperation>()
    private val selection =
        "${ContactsContract.Data.CONTACT_ID} =? AND ${ContactsContract.Data.MIMETYPE} =?"
    private val phoneNumberSelection =
        "$selection AND (${CommonDataKinds.Phone.NUMBER}=? OR ${CommonDataKinds.Phone.NORMALIZED_NUMBER}=?)"
    private val sipAddressSelection =
        "${ContactsContract.Data.CONTACT_ID} =? AND (${ContactsContract.Data.MIMETYPE} =? OR ${ContactsContract.Data.MIMETYPE} =?) AND data1=?"
    private val presenceUpdateSelection =
        "${ContactsContract.Data.CONTACT_ID} =? AND ${ContactsContract.Data.MIMETYPE} =? AND data3=?"
    private val contactUri = ContactsContract.Data.CONTENT_URI

    private var rawId: String? = null
    private var syncAccountRawId: String? = null
    private var pictureByteArray: ByteArray? = null

    init {
        Log.i("[Native Contact Editor] Using sync account $syncAccountName with type $syncAccountType")
        val contentResolver = coreContext.context.contentResolver
        val cursor = contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.CONTACT_ID} =?",
            arrayOf(contact.nativeId),
            null
        )
        if (cursor?.moveToFirst() == true) {
            do {
                if (rawId == null) {
                    rawId = cursor.getString(cursor.getColumnIndex(RawContacts._ID))
                    Log.d("[Native Contact Editor] Found raw id $rawId for native contact with id ${contact.nativeId}")
                }

                val accountType = cursor.getString(cursor.getColumnIndex(RawContacts.ACCOUNT_TYPE))
                if (accountType == syncAccountType && syncAccountRawId == null) {
                    syncAccountRawId = cursor.getString(cursor.getColumnIndex(RawContacts._ID))
                    Log.d("[Native Contact Editor] Found sync account raw id $syncAccountRawId for native contact with id ${contact.nativeId}")
                }
            } while (cursor.moveToNext() && syncAccountRawId == null)
        }
        cursor?.close()

        // When contact has been created with NativeContactEditor.createAndroidContact this is required
        if (rawId == null) rawId = contact.nativeId
    }

    fun setFirstAndLastNames(firstName: String, lastName: String): NativeContactEditor {
        if (firstName == contact.firstName && lastName == contact.lastName) {
            Log.w("[Native Contact Editor] First & last names haven't changed")
            return this
        }

        val builder = if (contact.firstName == null && contact.lastName == null) {
            // Probably a contact creation
            ContentProviderOperation.newInsert(contactUri)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        } else {
            ContentProviderOperation.newUpdate(contactUri)
                .withSelection(
                    selection,
                    arrayOf(contact.nativeId, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                )
        }

        builder.withValue(
            ContactsContract.Data.MIMETYPE,
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
        )
            .withValue(
                CommonDataKinds.StructuredName.GIVEN_NAME, firstName
            )
            .withValue(
                CommonDataKinds.StructuredName.FAMILY_NAME, lastName
            )
        addChanges(builder.build())
        return this
    }

    fun setOrganization(value: String): NativeContactEditor {
        val previousValue = contact.organization
        if (value == previousValue) {
            Log.d("[Native Contact Editor] Organization hasn't changed")
            return this
        }

        val builder = if (previousValue?.isNotEmpty() == true) {
            ContentProviderOperation.newUpdate(contactUri)
                .withSelection(
                    "$selection AND ${CommonDataKinds.Organization.COMPANY} =?",
                    arrayOf(
                        contact.nativeId,
                        CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                        previousValue
                    )
                )
        } else {
            ContentProviderOperation.newInsert(contactUri)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        }

        builder.withValue(
            ContactsContract.Data.MIMETYPE,
            CommonDataKinds.Organization.CONTENT_ITEM_TYPE
        )
            .withValue(
                CommonDataKinds.Organization.COMPANY, value
            )

        addChanges(builder.build())
        return this
    }

    fun setPhoneNumbers(value: List<NumberOrAddressEditorViewModel>): NativeContactEditor {
        var addCount = 0
        var removeCount = 0
        var editCount = 0

        for (phoneNumber in value) {
            when {
                phoneNumber.currentValue.isEmpty() -> {
                    // New phone number to add
                    val number = phoneNumber.newValue.value.orEmpty()
                    if (number.isNotEmpty()) {
                        addCount++
                        addPhoneNumber(number)
                    }
                }
                phoneNumber.toRemove.value == true -> {
                    // Existing number to remove
                    removeCount++
                    removePhoneNumber(phoneNumber.currentValue)
                }
                phoneNumber.currentValue != phoneNumber.newValue.value -> {
                    // Existing number to update
                    val number = phoneNumber.newValue.value.orEmpty()
                    if (number.isNotEmpty()) {
                        editCount++
                        updatePhoneNumber(phoneNumber.currentValue, number)
                    }
                }
            }
        }

        Log.i("[Native Contact Editor] $addCount numbers added, $removeCount numbers removed and $editCount numbers updated")
        return this
    }

    fun setSipAddresses(value: List<NumberOrAddressEditorViewModel>): NativeContactEditor {
        var addCount = 0
        var removeCount = 0
        var editCount = 0

        for (sipAddress in value) {
            when {
                sipAddress.currentValue.isEmpty() -> {
                    // New address to add
                    val address = sipAddress.newValue.value.orEmpty()
                    if (address.isNotEmpty()) {
                        addCount++
                        addSipAddress(address)
                    }
                }
                sipAddress.toRemove.value == true -> {
                    // Existing address to remove
                    removeCount++
                    removeLinphoneOrSipAddress(sipAddress.currentValue)
                }
                sipAddress.currentValue != sipAddress.newValue.value -> {
                    // Existing address to update
                    val address = sipAddress.newValue.value.orEmpty()
                    if (address.isNotEmpty()) {
                        editCount++
                        updateLinphoneOrSipAddress(sipAddress.currentValue, address)
                    }
                }
            }
        }

        Log.i("[Native Contact Editor] $addCount addresses added, $removeCount addresses removed and $editCount addresses updated")
        return this
    }

    fun setPicture(value: ByteArray?): NativeContactEditor {
        pictureByteArray = value
        if (value != null) Log.i("[Native Contact Editor] Adding operation: picture set/update")
        return this
    }

    fun ensureSyncAccountRawIdExists(): NativeContactEditor {
        if (syncAccountRawId == null) {
            Log.w("[Native Contact Editor] Sync account raw id not found")
            val insert = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, syncAccountType)
                .withValue(RawContacts.ACCOUNT_NAME, syncAccountName)
                .withValue(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DEFAULT)
                .build()
            addChanges(insert)
            val update =
                ContentProviderOperation.newUpdate(ContactsContract.AggregationExceptions.CONTENT_URI)
                    .withValue(
                        ContactsContract.AggregationExceptions.TYPE,
                        ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER
                    )
                    .withValue(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, rawId)
                    .withValueBackReference(
                        ContactsContract.AggregationExceptions.RAW_CONTACT_ID2,
                        0
                    )
                    .build()
            addChanges(update)
            commit()
        }
        return this
    }

    fun setPresenceInformation(phoneNumber: String, sipAddress: String): NativeContactEditor {
        if (syncAccountRawId == null) {
            Log.e("[Native Contact Editor] Can't add presence to contact in Linphone sync account, no raw id")
            return this
        }

        Log.d("[Native Contact Editor] Trying to add presence information to contact")
        setPresenceLinphoneSipAddressForPhoneNumber(sipAddress, phoneNumber)
        return this
    }

    fun commit() {
        if (PermissionHelper.get().hasWriteContactsPermission()) {
            try {
                if (changes.isNotEmpty()) {
                    val contentResolver = coreContext.context.contentResolver
                    val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, changes)
                    for (result in results) {
                        Log.i("[Native Contact Editor] Result is $result")
                        if (syncAccountRawId == null && result?.uri != null) {
                            syncAccountRawId = ContentUris.parseId(result.uri).toString()
                            Log.i("[Native Contact Editor] Sync account raw id is $syncAccountRawId")
                        }
                    }
                }
                if (pictureByteArray != null) {
                    updatePicture()
                }
            } catch (e: Exception) {
                Log.e("[Native Contact Editor] Exception raised while applying changes: $e")
            }
        } else {
            Log.e("[Native Contact Editor] WRITE_CONTACTS permission isn't granted!")
        }
        changes.clear()
    }

    private fun addChanges(operation: ContentProviderOperation) {
        Log.i("[Native Contact Editor] Adding operation: $operation")
        changes.add(operation)
    }

    private fun addPhoneNumber(phoneNumber: String) {
        val insert = ContentProviderOperation.newInsert(contactUri)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                CommonDataKinds.Phone.CONTENT_ITEM_TYPE
            )
            .withValue(CommonDataKinds.Phone.NUMBER, phoneNumber)
            .withValue(
                CommonDataKinds.Phone.TYPE,
                CommonDataKinds.Phone.TYPE_MOBILE
            )
            .build()
        addChanges(insert)
    }

    private fun updatePhoneNumber(currentValue: String, phoneNumber: String) {
        val update = ContentProviderOperation.newUpdate(contactUri)
            .withSelection(
                phoneNumberSelection,
                arrayOf(
                    contact.nativeId,
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    currentValue,
                    currentValue
                )
            )
            .withValue(ContactsContract.Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Phone.NUMBER, phoneNumber)
            .withValue(
                CommonDataKinds.Phone.TYPE,
                CommonDataKinds.Phone.TYPE_MOBILE
            )
            .build()
        addChanges(update)
    }

    private fun removePhoneNumber(phoneNumber: String) {
        val delete = ContentProviderOperation.newDelete(contactUri)
            .withSelection(
                phoneNumberSelection,
                arrayOf(
                    contact.nativeId,
                    CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    phoneNumber,
                    phoneNumber
                )
            )
            .build()
        addChanges(delete)
    }

    private fun addSipAddress(address: String) {
        val insert = ContentProviderOperation.newInsert(contactUri)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE
            )
            .withValue("data1", address) // value
            .build()
        addChanges(insert)
    }

    private fun updateLinphoneOrSipAddress(currentValue: String, sipAddress: String) {
        val update = ContentProviderOperation.newUpdate(contactUri)
            .withSelection(
                sipAddressSelection,
                arrayOf(
                    contact.nativeId,
                    CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
                    AppUtils.getString(R.string.linphone_address_mime_type),
                    currentValue
                )
            )
            .withValue(
                ContactsContract.Data.MIMETYPE,
                AppUtils.getString(R.string.linphone_address_mime_type)
            )
            .withValue("data1", sipAddress) // value
            .withValue("data2", AppUtils.getString(R.string.app_name)) // summary
            .withValue("data3", sipAddress) // detail
            .build()
        addChanges(update)
    }

    private fun removeLinphoneOrSipAddress(sipAddress: String) {
        val delete = ContentProviderOperation.newDelete(contactUri)
            .withSelection(
                sipAddressSelection,
                arrayOf(
                    contact.nativeId,
                    CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
                    AppUtils.getString(R.string.linphone_address_mime_type),
                    sipAddress
                )
            )
            .build()
        addChanges(delete)
    }

    private fun setPresenceLinphoneSipAddressForPhoneNumber(sipAddress: String, phoneNumber: String) {
        val contentResolver = coreContext.context.contentResolver
        val cursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf("data1"),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND data3 = ?",
            arrayOf(syncAccountRawId, AppUtils.getString(R.string.linphone_address_mime_type), phoneNumber),
            null
        )
        val count = cursor?.count ?: 0
        val data1 = if (count > 0) {
            if (cursor?.moveToFirst() == true) {
                cursor.getString(cursor.getColumnIndex("data1"))
            } else null
        } else null
        cursor?.close()

        if (count == 0) {
            Log.i("[Native Contact Editor] No existing presence information found for this phone number & SIP address, let's add it")
            addPresenceLinphoneSipAddressForPhoneNumber(sipAddress, phoneNumber)
        } else {
            if (data1 != null && data1 == sipAddress) {
                Log.d("[Native Contact Editor] There is already an entry for this phone number and SIP address, skipping")
            } else {
                Log.w("[Native Contact Editor] There is already an entry for this phone number but not for the same SIP address")
                updatePresenceLinphoneSipAddressForPhoneNumber(sipAddress, phoneNumber)
            }
        }
    }

    private fun addPresenceLinphoneSipAddressForPhoneNumber(address: String, detail: String) {
        val insert = ContentProviderOperation.newInsert(contactUri)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, syncAccountRawId)
            .withValue(
                ContactsContract.Data.MIMETYPE,
                AppUtils.getString(R.string.linphone_address_mime_type)
            )
            .withValue("data1", address) // value
            .withValue("data2", AppUtils.getString(R.string.app_name)) // summary
            .withValue("data3", detail) // detail
            .build()
        addChanges(insert)
    }

    private fun updatePresenceLinphoneSipAddressForPhoneNumber(
        sipAddress: String,
        phoneNumber: String
    ) {
        val update = ContentProviderOperation.newUpdate(contactUri)
            .withSelection(
                presenceUpdateSelection,
                arrayOf(
                    contact.nativeId,
                    AppUtils.getString(R.string.linphone_address_mime_type),
                    phoneNumber
                )
            )
            .withValue(
                ContactsContract.Data.MIMETYPE,
                AppUtils.getString(R.string.linphone_address_mime_type)
            )
            .withValue("data1", sipAddress) // value
            .withValue("data2", AppUtils.getString(R.string.app_name)) // summary
            .withValue("data3", phoneNumber) // detail
            .build()
        addChanges(update)
    }

    private fun updatePicture() {
        val value = pictureByteArray
        val id = rawId
        if (value == null || id == null) return

        try {
            val uri = Uri.withAppendedPath(
                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id.toLong()),
                RawContacts.DisplayPhoto.CONTENT_DIRECTORY
            )
            val contentResolver = coreContext.context.contentResolver
            val assetFileDescriptor = contentResolver.openAssetFileDescriptor(uri, "rw")
            val outputStream = assetFileDescriptor?.createOutputStream()
            outputStream?.write(value)
            outputStream?.close()
            assetFileDescriptor?.close()
            Log.i("[Native Contact Editor] Picture updated")
        } catch (e: Exception) {
            Log.e("[Native Contact Editor] Failed to update picture, raised exception: $e")
        }

        pictureByteArray = null
    }
}