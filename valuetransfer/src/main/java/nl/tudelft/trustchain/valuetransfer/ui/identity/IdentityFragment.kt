package nl.tudelft.trustchain.valuetransfer.ui.identity

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.Item
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.schema.SchemaManager
import nl.tudelft.ipv8.attestation.wallet.AttestationBlob
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.util.QRCodeUtils
import nl.tudelft.trustchain.valuetransfer.util.copyToClipboard
import nl.tudelft.trustchain.valuetransfer.util.mapToJSON
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.valuetransfer.R
import nl.tudelft.trustchain.valuetransfer.ui.VTFragment
import nl.tudelft.trustchain.valuetransfer.ValueTransferMainActivity
import nl.tudelft.trustchain.valuetransfer.databinding.FragmentIdentityBinding
import nl.tudelft.trustchain.valuetransfer.dialogs.*
import nl.tudelft.trustchain.valuetransfer.entity.IdentityAttribute
import nl.tudelft.trustchain.valuetransfer.entity.Identity
import nl.tudelft.trustchain.valuetransfer.ui.QRScanController
import org.json.JSONObject
import java.util.*

class IdentityFragment : VTFragment(R.layout.fragment_identity) {
    private val binding by viewBinding(FragmentIdentityBinding::bind)

    private val adapterIdentity = ItemAdapter()
    private val adapterAttributes = ItemAdapter()
    private val adapterAttestations = ItemAdapter()

    private val itemsIdentity: LiveData<List<Item>> by lazy {
        getIdentityStore().getAllIdentities().map { identities ->
            createIdentityItems(identities)
        }.asLiveData()
    }

    private val itemsAttributes: LiveData<List<Item>> by lazy {
        getIdentityStore().getAllAttributes().map { attributes ->
            createAttributeItems(attributes)
        }.asLiveData()
    }

    private var scanIntent: Int = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_identity, container, false)
    }

    override fun onResume() {
        super.onResume()

        parentActivity.apply {
            setActionBarTitle(resources.getString(R.string.menu_navigation_identity), null)
            toggleActionBar(false)
            toggleBottomNavigation(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        adapterIdentity.registerRenderer(
            IdentityItemRenderer(
                1,
                { identity ->
                    val map = mapOf(
                        QRScanController.KEY_PUBLIC_KEY to identity.publicKey.keyToBin().toHex(),
                        QRScanController.KEY_NAME to identity.content.givenNames
                    )

                    QRCodeDialog(resources.getString(R.string.text_my_public_key), resources.getString(R.string.text_public_key_share_desc), mapToJSON(map).toString())
                        .show(parentFragmentManager, tag)
                },
                { identity ->
                    copyToClipboard(
                        requireContext(),
                        identity.publicKey.keyToBin().toHex(),
                        resources.getString(R.string.text_public_key)
                    )
                    parentActivity.displaySnackbar(
                        requireContext(),
                        resources.getString(
                            R.string.snackbar_copied_clipboard,
                            resources.getString(R.string.text_public_key)
                        )
                    )
                }
            )
        )

        adapterAttributes.registerRenderer(
            IdentityAttributeItemRenderer(
                { attribute ->
                    ConfirmDialog(
                        resources.getString(
                            R.string.text_confirm_delete,
                            resources.getString(R.string.text_this_attribute)
                        )
                    ) { dialog ->
                        try {
                            getIdentityStore().deleteAttribute(attribute)

                            activity?.invalidateOptionsMenu()
                            dialog.dismiss()

                            parentActivity.displaySnackbar(
                                requireContext(),
                                resources.getString(R.string.snackbar_identity_attribute_remove_success)
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            parentActivity.displaySnackbar(
                                requireContext(),
                                resources.getString(R.string.snackbar_identity_attribute_remove_error),
                                type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                            )
                        }
                    }
                        .show(parentFragmentManager, tag)
                },
                { attribute ->
                    IdentityAttributeDialog(attribute).show(parentFragmentManager, tag)
                },
                { attribute ->
                    IdentityAttributeShareDialog(null, attribute).show(parentFragmentManager, tag)
                }
            )
        )

        adapterAttestations.registerRenderer(
            AttestationItemRenderer(
                parentActivity,
                {
                    val blob = it.attestationBlob

                    if (blob.signature != null) {
                        val manager = SchemaManager()
                        manager.registerDefaultSchemas()
                        val attestation = manager.deserialize(blob.blob, blob.idFormat)
                        val parsedMetadata = JSONObject(blob.metadata!!)

                        val map = mapOf(
                            QRScanController.KEY_PRESENTATION to QRScanController.VALUE_ATTESTATION,
                            QRScanController.KEY_METADATA to blob.metadata,
                            QRScanController.KEY_ATTESTATION_HASH to attestation.getHash().toHex(),
                            QRScanController.KEY_SIGNATURE to blob.signature!!.toHex(),
                            QRScanController.KEY_SIGNEE_KEY to IPv8Android.getInstance().myPeer.publicKey.keyToBin().toHex(),
                            QRScanController.KEY_ATTESTOR_KEY to blob.attestorKey!!.keyToBin().toHex()
                        )

                        QRCodeDialog(
                            resources.getString(R.string.dialog_title_attestation),
                            StringBuilder()
                                .append(
                                    parsedMetadata.optString(
                                        QRScanController.KEY_ATTRIBUTE,
                                        QRScanController.FALLBACK_UNKNOWN
                                    )
                                )
                                .append(
                                    parsedMetadata.optString(
                                        QRScanController.KEY_VALUE,
                                        QRScanController.FALLBACK_UNKNOWN
                                    )
                                )
                                .toString(),
                            mapToJSON(map).toString()
                        )
                            .show(parentFragmentManager, tag)
                    } else {
                        deleteAttestation(it)
                    }
                }
            ) {
                deleteAttestation(it)
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        onResume()

        binding.rvIdentities.adapter = adapterIdentity
        binding.rvIdentities.layoutManager = LinearLayoutManager(context)

        binding.rvAttributes.adapter = adapterAttributes
        binding.rvAttributes.layoutManager = LinearLayoutManager(context)

        binding.rvAttestations.adapter = adapterAttestations
        binding.rvAttestations.layoutManager = LinearLayoutManager(context)

        itemsIdentity.observe(
            viewLifecycleOwner, {
                adapterIdentity.updateItems(it)
                toggleVisibility()
            }
        )

        lifecycleScope.launchWhenStarted {
            while (isActive) {
                updateAttestations()
                delay(1000)
            }
        }

        itemsAttributes.observe(
            viewLifecycleOwner, {
                adapterAttributes.updateItems(it)
                toggleVisibility()
            }
        )

        binding.ivAddAttribute.setOnClickListener {
            addAttribute()
        }

        binding.ivAddAttestationAuthority.setOnClickListener {
            OptionsDialog(R.menu.identity_attestations_options, "Choose Option") { _, item ->
                when (item.itemId) {
                    R.id.actionAddAttestation -> addAttestation()
                    R.id.actionAddAuthority -> addAuthority()
                }
            }.show(parentFragmentManager, tag)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        menu.clear()
        menu.add(Menu.NONE, MENU_ITEM_OPTIONS, Menu.NONE, null)
            .setIcon(R.drawable.ic_baseline_more_vert_24)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        OptionsDialog(
            R.menu.identity_options,
            "Choose Option") { _, selectedItem ->
                when (selectedItem.itemId) {
                    R.id.actionEditIdentity -> {
                        IdentityDetailsDialog().show(parentFragmentManager, tag)
                    }
                    R.id.actionRemoveIdentity -> {
                        ConfirmDialog(
                            resources.getString(
                                R.string.text_confirm_delete,
                                resources.getString(R.string.text_identity)
                            )
                        ) {
                            try {
                                getIdentityStore().deleteAllAttributes()

                                getAttestationCommunity().database.getAllAttestations().forEach {
                                    getAttestationCommunity().database.deleteAttestationByHash(it.attestationHash)
                                }

                                val identity = getIdentityStore().getIdentity()
                                if (identity != null) {
                                    getIdentityStore().deleteIdentity(identity)
                                }

                                parentActivity.reloadActivity()
                                parentActivity.displaySnackbar(
                                    requireContext(),
                                    resources.getString(R.string.snackbar_identity_remove_success),
                                    isShort = false
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                parentActivity.displaySnackbar(
                                    requireContext(),
                                    resources.getString(R.string.snackbar_identity_remove_error),
                                    type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                                )
                            }
                        }
                            .show(parentFragmentManager, tag)
                    }
                    R.id.actionViewAuthorities -> IdentityAttestationAuthoritiesDialog(
                        trustchain.getMyPublicKey().toHex()
                    ).show(parentFragmentManager, tag)
                }
            }.show(parentFragmentManager, tag)

        return super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        QRCodeUtils(requireContext()).parseActivityResult(requestCode, resultCode, data)?.let { result ->
            try {
                val obj = JSONObject(result)

                if (obj.has(QRScanController.KEY_PUBLIC_KEY)) {
                    try {
                        defaultCryptoProvider.keyFromPublicBin(obj.optString(QRScanController.KEY_PUBLIC_KEY).hexToBytes())
                        val publicKey = obj.optString(QRScanController.KEY_PUBLIC_KEY)

                        when (scanIntent) {
                            ADD_ATTESTATION_INTENT -> getQRScanController().addAttestation(publicKey)
                            ADD_AUTHORITY_INTENT -> getQRScanController().addAuthority(publicKey)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        parentActivity.displaySnackbar(
                            requireContext(),
                            resources.getString(R.string.snackbar_invalid_public_key),
                            type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                        )
                    }
                } else {
                    parentActivity.displaySnackbar(
                        requireContext(),
                        resources.getString(R.string.snackbar_no_public_key_found),
                        type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                parentActivity.displaySnackbar(
                    requireContext(),
                    resources.getString(R.string.snackbar_qr_code_not_json_format),
                    type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                )
            }
        }
    }

    private fun deleteAttestation(attestation: AttestationItem) {
        ConfirmDialog(
            resources.getString(
                R.string.text_confirm_delete,
                resources.getString(R.string.text_this_attestation)
            )
        ) { dialog ->
            try {
                getAttestationCommunity().database.deleteAttestationByHash(attestation.attestationBlob.attestationHash)
                updateAttestations()

                dialog.dismiss()
                parentActivity.displaySnackbar(
                    requireContext(),
                    resources.getString(R.string.snackbar_attestation_remove_success)
                )
            } catch (e: Exception) {
                e.printStackTrace()
                parentActivity.displaySnackbar(
                    requireContext(),
                    resources.getString(R.string.snackbar_attestation_remove_error),
                    type = ValueTransferMainActivity.SNACKBAR_TYPE_ERROR
                )
            }
        }
            .show(parentFragmentManager, tag)
    }

    private fun toggleVisibility() {
        binding.tvNoAttestations.isVisible = adapterAttestations.itemCount == 0
        binding.tvNoAttributes.isVisible = adapterAttributes.itemCount == 0
        binding.ivAddAttribute.isVisible = getIdentityCommunity().getUnusedAttributeNames().isNotEmpty()
    }

    private fun updateAttestations() {
        val oldCount = adapterAttestations.itemCount
        val itemsAttestations = getAttestationCommunity().database.getAllAttestations()

        if (oldCount != itemsAttestations.size) {
            adapterAttestations.updateItems(
                createAttestationItems(itemsAttestations)
            )

            binding.rvAttestations.setItemViewCacheSize(itemsAttestations.size)
        }

        toggleVisibility()
    }

    private fun addAttribute() {
        IdentityAttributeDialog(null).show(parentFragmentManager, tag)
    }

    private fun addAttestation() {
        scanIntent = ADD_ATTESTATION_INTENT
        QRCodeUtils(requireContext()).startQRScanner(
            this,
            promptText = resources.getString(R.string.text_scan_public_key_to_add_attestation),
            vertical = true
        )
    }

    private fun addAuthority() {
        scanIntent = ADD_AUTHORITY_INTENT
        QRCodeUtils(requireContext()).startQRScanner(
            this,
            promptText = resources.getString(R.string.text_scan_public_key_to_add_authority),
            vertical = true
        )
    }

    private fun createAttributeItems(attributes: List<IdentityAttribute>): List<Item> {
        return attributes.map { attribute ->
            IdentityAttributeItem(attribute)
        }
    }

    private fun createAttestationItems(attestations: List<AttestationBlob>): List<Item> {
        return attestations
            .map { blob ->
                AttestationItem(blob)
            }
            .sortedBy {
                if (it.attestationBlob.metadata != null) {
                    return@sortedBy JSONObject(it.attestationBlob.metadata!!).optString(QRScanController.KEY_ATTRIBUTE)
                } else {
                    return@sortedBy ""
                }
            }
    }

    private fun createIdentityItems(identities: List<Identity>): List<Item> {
        return identities.map { identity ->
            IdentityItem(
                identity
            )
        }
    }

    companion object {
        private const val ADD_ATTESTATION_INTENT = 0
        private const val ADD_AUTHORITY_INTENT = 1
        private const val MENU_ITEM_OPTIONS = 1234
    }
}
