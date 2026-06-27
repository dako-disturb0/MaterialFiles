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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditMediafireServerFragmentBinding
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

class EditMediaFireServerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { EditMediaFireServerViewModel() } }

    private lateinit var binding: EditMediafireServerFragmentBinding

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
        EditMediafireServerFragmentBinding.inflate(inflater, container, false)
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
                    R.string.storage_edit_mediafire_server_title_edit
                } else {
                    R.string.storage_edit_mediafire_server_title_add
                }
            )
        }

        binding.emailEdit.hideTextInputLayoutErrorOnTextChange(binding.emailLayout)
        binding.emailEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.passwordEdit.hideTextInputLayoutErrorOnTextChange(binding.passwordLayout)
        binding.appIdEdit.hideTextInputLayoutErrorOnTextChange(binding.appIdLayout)
        binding.apiKeyEdit.hideTextInputLayoutErrorOnTextChange(binding.apiKeyLayout)

        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_mediafire_server_connect_and_add
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
            if (args.server != null) R.string.remove else R.string.storage_edit_mediafire_server_add
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
                binding.emailEdit.setText(server.email)
                binding.passwordEdit.setText(server.password)
                binding.appIdEdit.setText(server.appId)
                binding.apiKeyEdit.setText(server.apiKey)
                binding.nameEdit.setText(server.customName)
            }
        }
    }

    private fun updateNamePlaceholder() {
        val email = binding.emailEdit.text.toString().takeIfNotEmpty()
        binding.nameLayout.placeholderText = email ?: getString(R.string.storage_edit_mediafire_server_name_placeholder)
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

    private fun onConnectStateChanged(state: ActionState<MediaFireServer, Unit>) {
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

    private fun getServerOrSetError(): MediaFireServer? {
        var errorEdit: TextInputEditText? = null

        val email = binding.emailEdit.text.toString().takeIfNotEmpty()
        if (email == null) {
            binding.emailLayout.error = getString(R.string.storage_edit_mediafire_server_email_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.emailEdit
            }
        }

        val password = binding.passwordEdit.text.toString().takeIfNotEmpty()
        if (password == null) {
            binding.passwordLayout.error = getString(R.string.storage_edit_mediafire_server_password_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.passwordEdit
            }
        }

        val appId = binding.appIdEdit.text.toString().takeIfNotEmpty()
        if (appId == null) {
            binding.appIdLayout.error = getString(R.string.storage_edit_mediafire_server_app_id_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.appIdEdit
            }
        }

        val apiKey = binding.apiKeyEdit.text.toString().takeIfNotEmpty()
        if (apiKey == null) {
            binding.apiKeyLayout.error = getString(R.string.storage_edit_mediafire_server_api_key_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.apiKeyEdit
            }
        }

        val name = binding.nameEdit.text.toString().takeIfNotEmpty()

        if (errorEdit != null) {
            errorEdit.requestFocus()
            return null
        }

        return MediaFireServer(args.server?.id, name, email!!, password!!, appId!!, apiKey!!)
    }

    @Parcelize
    class Args(
        val server: MediaFireServer? = null
    ) : ParcelableArgs
}
