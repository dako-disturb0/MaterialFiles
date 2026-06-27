/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditGdriveServerFragmentBinding
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.setResult
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.viewModels
import org.json.JSONObject

class EditGoogleDriveServerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { EditGoogleDriveServerViewModel() } }

    private lateinit var binding: EditGdriveServerFragmentBinding

    private val pickJsonLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val content = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().use { it.readText() }
                }
                if (content != null) {
                    binding.serviceAccountJsonEdit.setText(content)
                    updateNamePlaceholder()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Failed to read JSON: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenStarted {
            launch { viewModel.connectState.collect { onConnectStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        EditGdriveServerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            activity.setTitle(
                if (args.server != null) {
                    R.string.storage_edit_gdrive_server_title_edit
                } else {
                    R.string.storage_edit_gdrive_server_title_add
                }
            )
        }

        binding.serviceAccountJsonEdit.hideTextInputLayoutErrorOnTextChange(binding.serviceAccountJsonLayout)
        binding.serviceAccountJsonEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.rootFolderIdEdit.hideTextInputLayoutErrorOnTextChange(binding.rootFolderIdLayout)

        binding.importJsonButton.setOnClickListener {
            pickJsonLauncher.launch("application/json")
        }

        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_gdrive_server_connect_and_add
            }
        )
        binding.saveOrConnectAndAddButton.setOnClickListener {
            if (args.server != null) {
                saveOrAdd()
            } else {
                connectAndAdd()
            }
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.removeOrAddButton.setText(
            if (args.server != null) R.string.remove else R.string.storage_edit_gdrive_server_add
        )
        binding.removeOrAddButton.setOnClickListener {
            if (args.server != null) {
                remove()
            } else {
                saveOrAdd()
            }
        }

        if (savedInstanceState == null) {
            val server = args.server
            if (server != null) {
                binding.serviceAccountJsonEdit.setText(server.serviceAccountJson)
                binding.rootFolderIdEdit.setText(server.rootFolderId)
                binding.nameEdit.setText(server.customName)
                binding.viewWithMediaCacheSwitch.isChecked = server.viewWithMediaCache
            } else {
                binding.rootFolderIdEdit.setText("root")
            }
        }
    }

    private fun updateNamePlaceholder() {
        val jsonStr = binding.serviceAccountJsonEdit.text.toString().takeIfNotEmpty()
        var email: String? = null
        if (jsonStr != null) {
            try {
                val obj = JSONObject(jsonStr)
                email = obj.optString("client_email").takeIfNotEmpty()
            } catch (ignored: Exception) {}
        }
        binding.nameLayout.placeholderText = email ?: getString(R.string.storage_edit_gdrive_server_name_placeholder)
    }

    private fun saveOrAdd() {
        val server = getServerOrSetError() ?: return
        Storages.addOrReplace(server)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun connectAndAdd() {
        if (!viewModel.connectState.value.isReady) {
            return
        }
        val server = getServerOrSetError() ?: return
        viewModel.connect(server)
    }

    private fun onConnectStateChanged(state: ActionState<GoogleDriveServer, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {
                val isConnecting = state is ActionState.Running
                binding.progress.fadeToVisibilityUnsafe(isConnecting)
                binding.scrollView.fadeToVisibilityUnsafe(!isConnecting)
                binding.saveOrConnectAndAddButton.isEnabled = !isConnecting
                binding.removeOrAddButton.isEnabled = !isConnecting
            }
            is ActionState.Success -> {
                Storages.addOrReplace(state.argument)
                setResult(Activity.RESULT_OK)
                finish()
            }
            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.printStackTrace()
                showToast(throwable.toString())
                viewModel.finishConnecting()
            }
        }
    }

    private fun remove() {
        Storages.remove(args.server!!)
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun getServerOrSetError(): GoogleDriveServer? {
        var errorEdit: TextInputEditText? = null

        val jsonStr = binding.serviceAccountJsonEdit.text.toString().takeIfNotEmpty()
        var clientEmail: String? = null
        if (jsonStr == null) {
            binding.serviceAccountJsonLayout.error = getString(R.string.storage_edit_gdrive_server_json_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.serviceAccountJsonEdit
            }
        } else {
            try {
                val obj = JSONObject(jsonStr)
                clientEmail = obj.optString("client_email").takeIfNotEmpty()
                if (clientEmail == null) {
                    binding.serviceAccountJsonLayout.error = getString(R.string.storage_edit_gdrive_server_json_error_invalid_email)
                    if (errorEdit == null) {
                        errorEdit = binding.serviceAccountJsonEdit
                    }
                }
            } catch (e: Exception) {
                binding.serviceAccountJsonLayout.error = getString(R.string.storage_edit_gdrive_server_json_error_invalid_json)
                if (errorEdit == null) {
                    errorEdit = binding.serviceAccountJsonEdit
                }
            }
        }

        val rootFolderId = binding.rootFolderIdEdit.text.toString().takeIfNotEmpty() ?: "root"
        val name = binding.nameEdit.text.toString().takeIfNotEmpty()
        val viewWithMediaCache = binding.viewWithMediaCacheSwitch.isChecked

        if (errorEdit != null) {
            errorEdit.requestFocus()
            return null
        }

        return GoogleDriveServer(args.server?.id, name, clientEmail!!, jsonStr!!, viewWithMediaCache, rootFolderId)
    }

    @Parcelize
    class Args(
        val server: GoogleDriveServer? = null
    ) : ParcelableArgs
}
