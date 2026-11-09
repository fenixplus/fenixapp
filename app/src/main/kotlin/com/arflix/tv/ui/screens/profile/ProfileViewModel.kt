package com.arflix.tv.ui.screens.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.R
import com.arflix.tv.data.model.KidsAgeLevel
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileAvatarImageManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchHistoryRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.IptvRepository
import com.arflix.tv.ui.components.ToastType
import com.arflix.tv.util.PinUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class ProfileUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val isLoading: Boolean = true,
    val isSwitchingProfile: Boolean = false,
    val isManageMode: Boolean = false,
    // Add profile dialog state
    val showAddDialog: Boolean = false,
    val newProfileName: String = "",
    val selectedColorIndex: Int = 0,
    val selectedAvatarId: Int = 0, // 0 = legacy letter, 1-24 = cartoon avatar
    val selectedAvatarImageUri: String? = null,
    val useCustomAvatarImage: Boolean = false,
    val isKidsProfile: Boolean = false,
    val kidsAgeLevel: KidsAgeLevel = KidsAgeLevel.DEFAULT,
    // Hashed parent PIN chosen in this dialog, before the profile is saved with it.
    val pendingParentPin: String? = null,
    // Edit profile dialog state
    val editingProfile: Profile? = null,
    // Toast state
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.SUCCESS,
    val showToast: Boolean = false,
    // PIN dialog state
    val showPinDialog: Boolean = false,
    val pinDialogMode: String = "", // "verify" or "setup"
    val pendingProfileForPin: Profile? = null,
    val pinContext: String = "", // "select", "edit", or "delete"
    val pinError: String = "", // Error message for wrong PIN
    val isCatalogSyncing: Boolean = false,
    val isOffline: Boolean = false
)

internal fun ProfileUiState.canFinishSelection(targetProfileId: String?): Boolean =
    targetProfileId != null && activeProfile?.id == targetProfileId &&
        !isSwitchingProfile && !isManageMode && !showPinDialog && (!isCatalogSyncing || isOffline)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val traktRepository: TraktRepository,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val watchlistRepository: WatchlistRepository,
    private val iptvRepository: IptvRepository,
    private val profileAvatarImageManager: ProfileAvatarImageManager,
    private val cloudSyncRepository: CloudSyncRepository,
    private val fenixRepository: com.arflix.tv.data.telegram.FenixRepository,
    private val networkMonitor: com.arflix.tv.network.NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private var lastInitialRestoreUserId: String? = null

    init {
        loadProfiles()
        observeProfiles()
        restoreCloudProfilesForFreshLogin()
        observeCatalogSync()
        observeNetworkState()
    }

    private fun observeNetworkState() {
        _uiState.value = _uiState.value.copy(isOffline = !networkMonitor.isConnected)
        viewModelScope.launch {
            networkMonitor.isConnectedFlow.collect { connected ->
                _uiState.value = _uiState.value.copy(isOffline = !connected)
            }
        }
    }

    private fun observeCatalogSync() {
        viewModelScope.launch {
            fenixRepository.isCatalogSyncing.collect { syncing ->
                _uiState.value = _uiState.value.copy(isCatalogSyncing = syncing)
            }
        }
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val profiles = profileRepository.getProfiles()
            val activeProfile = profileRepository.getActiveProfile()
            _uiState.value = _uiState.value.copy(
                profiles = profiles,
                activeProfile = activeProfile,
                isLoading = false
            )
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                _uiState.value = _uiState.value.copy(
                    profiles = profiles,
                    isLoading = _uiState.value.isLoading && profiles.isEmpty()
                )
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile.collect { profile ->
                _uiState.value = _uiState.value.copy(activeProfile = profile)
            }
        }
    }

    private fun restoreCloudProfilesForFreshLogin() {
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                if (state !is AuthState.Authenticated) {
                    lastInitialRestoreUserId = null
                    if (_uiState.value.isLoading) {
                        loadProfiles()
                    }
                    return@collect
                }

                val userId = state.userId
                if (lastInitialRestoreUserId == userId || cloudSyncRepository.hasMeaningfulLocalProfiles()) {
                    return@collect
                }

                lastInitialRestoreUserId = userId
                // Keep profile selector responsive while cloud restore runs. If we
                // already have local profiles, only show them first and restore in
                // the background.
                val hasLocalProfiles = runCatching { profileRepository.getProfiles() }
                    .getOrNull()
                    ?.isNotEmpty() == true
                if (!hasLocalProfiles) {
                    _uiState.value = _uiState.value.copy(isLoading = true)
                }

                var restoreResult = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(18_000L) {
                        cloudSyncRepository.pullFromCloud(pushPendingLocalFirst = false)
                    } ?: CloudSyncRepository.RestoreResult.FAILED
                }
                if (restoreResult == CloudSyncRepository.RestoreResult.FAILED) {
                    delay(1_200L)
                    restoreResult = withContext(Dispatchers.IO) {
                        withTimeoutOrNull(18_000L) {
                            cloudSyncRepository.pullFromCloud(pushPendingLocalFirst = false)
                        } ?: CloudSyncRepository.RestoreResult.FAILED
                    }
                }

                val profiles = profileRepository.getProfiles()
                val activeProfile = profileRepository.getActiveProfile()
                _uiState.value = _uiState.value.copy(
                    profiles = profiles,
                    activeProfile = activeProfile,
                    isLoading = false
                )
            }
        }
    }

    private fun showToast(message: String, type: ToastType = ToastType.SUCCESS) {
        _uiState.value = _uiState.value.copy(
            toastMessage = message,
            toastType = type,
            showToast = true
        )
        viewModelScope.launch {
            delay(3500)
            _uiState.value = _uiState.value.copy(showToast = false)
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(showToast = false)
    }

    /**
     * Preload Continue Watching data when a profile is focused (before selection).
     * This enables instant display when the user actually selects the profile.
     */
    fun preloadForProfile(profile: Profile) {
        viewModelScope.launch {
            traktRepository.preloadContinueWatchingForProfile(profile.id)
        }
    }

    fun selectProfile(profile: Profile) {
        if (_uiState.value.isSwitchingProfile) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSwitchingProfile = true
            )
            try {
                val previousProfileId = withContext(Dispatchers.IO) {
                    profileRepository.getActiveProfileId()
                }
                val isSameProfile = previousProfileId == profile.id
                withContext(Dispatchers.Default) {
                    // CRITICAL: Clear ALL profile caches BEFORE switching to ensure complete isolation.
                    // Keep this off the main thread; some profiles carry enough data here to stall
                    // touch devices during the profile tap transition.
                    if (!isSameProfile) {
                        traktRepository.clearAllProfileCaches()
                        watchHistoryRepository.clearProfileCaches()
                        watchlistRepository.clearWatchlistCache()
                        iptvRepository.invalidateCache()
                    }
                }

                // Update ProfileManager cache immediately so profile-scoped keys are correct
                // for any work started after selection.
                profileManager.setCurrentProfileId(profile.id)
                profileManager.setCurrentProfileName(profile.name)

                // Activate any preloaded Continue Watching cache for instant Home population.
                traktRepository.activatePreloadedCache(profile.id)

                // Persist only actual profile changes. Re-selecting the current
                // profile used to mark the full cloud payload dirty on every launch.
                if (!isSameProfile) {
                    withContext(Dispatchers.IO) {
                        profileRepository.setActiveProfile(profile.id)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    activeProfile = profile,
                    isOffline = !networkMonitor.isConnected
                )

                // Sincroniza/verifica o catálogo Fenix antes de concluir a troca de perfil apenas se online
                if (networkMonitor.isConnected) {
                    withContext(Dispatchers.IO) {
                        runCatching { fenixRepository.syncCatalog() }
                    }
                }

                viewModelScope.launch(Dispatchers.IO) {
                    if (profileRepository.getActiveProfileId() != profile.id) return@launch
                    runCatching { iptvRepository.warmupFromCacheOnly() }
                }

                // Home performs one throttled cloud pull after its local snapshot is
                // visible. Doing two pulls plus a forced megabyte-sized push here made
                // catalog loading compete with cloud serialization and network traffic.

                // Defer network parsing to keep initial Home navigation smooth. The
                // cache-only warmup above is safe to run immediately because it never
                // touches the provider.
                viewModelScope.launch(Dispatchers.IO) {
                    delay(45_000L)
                    if (profileRepository.getActiveProfileId() != profile.id) return@launch
                    runCatching {
                        iptvRepository.prefetchFreshStartupData()
                    }
                }
            } finally {
                _uiState.value = _uiState.value.copy(isSwitchingProfile = false)
            }
        }
    }

    fun switchProfile() {
        // Clear all caches when leaving a profile to prevent data leakage
        traktRepository.clearAllProfileCaches()
        watchHistoryRepository.clearProfileCaches()
        watchlistRepository.clearWatchlistCache()
        iptvRepository.invalidateCache()

        viewModelScope.launch {
            profileRepository.clearActiveProfile()
        }
    }

    // ========== Add Profile ==========

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            newProfileName = "",
            selectedColorIndex = (_uiState.value.profiles.size) % ProfileColors.colors.size,
            selectedAvatarId = 0,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false,
            isKidsProfile = false,
            kidsAgeLevel = KidsAgeLevel.DEFAULT,
            pendingParentPin = null
        )
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun setNewProfileName(name: String) {
        _uiState.value = _uiState.value.copy(newProfileName = name)
    }

    fun setSelectedColorIndex(index: Int) {
        _uiState.value = _uiState.value.copy(
            selectedColorIndex = index,
            selectedAvatarId = 0,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun setSelectedAvatarId(id: Int) {
        _uiState.value = _uiState.value.copy(
            selectedAvatarId = id,
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun setSelectedAvatarImage(uri: String) {
        _uiState.value = _uiState.value.copy(
            selectedAvatarImageUri = uri,
            useCustomAvatarImage = true,
            selectedAvatarId = 0
        )
    }

    fun removeSelectedAvatarImage() {
        _uiState.value = _uiState.value.copy(
            selectedAvatarImageUri = null,
            useCustomAvatarImage = false
        )
    }

    fun setKidsProfile(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isKidsProfile = enabled)
    }

    fun setKidsAgeLevel(level: KidsAgeLevel) {
        _uiState.value = _uiState.value.copy(kidsAgeLevel = level)
    }

    fun createProfile() {
        val state = _uiState.value
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            val existingParentPin = profileRepository.getParentPin()
            if (state.isKidsProfile && existingParentPin.isNullOrEmpty() && state.pendingParentPin == null) {
                requestParentPinSetup(PIN_CONTEXT_PARENT_SETUP_CREATE)
                return@launch
            }
            val parentPinToUse = state.pendingParentPin ?: existingParentPin
            val profile = profileRepository.createProfile(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = state.isKidsProfile,
                kidsAgeLevel = state.kidsAgeLevel.name.takeIf { state.isKidsProfile },
                parentPin = parentPinToUse.takeIf { state.isKidsProfile }
            )
            val selectedImage = state.selectedAvatarImageUri
            if (state.useCustomAvatarImage && !selectedImage.isNullOrBlank()) {
                runCatching {
                    profileAvatarImageManager.importAvatar(profile.id, selectedImage)
                }.onSuccess { imported ->
                    profileRepository.updateProfile(
                        profile.copy(
                            avatarId = 0,
                            avatarImageVersion = imported.version,
                            avatarImageStoragePath = imported.storagePath
                        )
                    )
                }.onFailure {
                    showToast(context.getString(R.string.profile_avatar_import_failed), ToastType.ERROR)
                }
            }
            _uiState.value = _uiState.value.copy(showAddDialog = false, pendingParentPin = null)
            showToast(context.getString(R.string.profile_created_success), ToastType.SUCCESS)
            runCatching { cloudSyncRepository.pushToCloud(force = true) }
        }
    }

    // ========== Edit Profile ==========

    fun showEditDialog(profile: Profile) {
        // A kids profile's restrictions belong to the parent: its parent PIN, not access to the
        // profile itself, opens the editor (where kids mode and the age limit can change).
        if (profile.isKidsProfile && !profile.parentPin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = PIN_CONTEXT_PARENT_EDIT
            )
        } else if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "edit"
            )
        } else {
            _uiState.value = _uiState.value.copy(
                editingProfile = profile,
                newProfileName = profile.name,
                selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
                selectedAvatarId = profile.avatarId,
                selectedAvatarImageUri = null,
                useCustomAvatarImage = profile.avatarImageVersion > 0L,
                isKidsProfile = profile.isKidsProfile,
                kidsAgeLevel = KidsAgeLevel.fromName(profile.kidsAgeLevel),
                pendingParentPin = null
            )
        }
    }

    fun hideEditDialog() {
        _uiState.value = _uiState.value.copy(editingProfile = null)
    }

    fun updateProfile() {
        val state = _uiState.value
        val editing = state.editingProfile ?: return
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            val existingParentPin = profileRepository.getParentPin()
            if (state.isKidsProfile && existingParentPin.isNullOrEmpty() && state.pendingParentPin == null) {
                requestParentPinSetup(PIN_CONTEXT_PARENT_SETUP_EDIT)
                return@launch
            }
            val parentPinToUse = state.pendingParentPin ?: existingParentPin
            var updatedProfile = editing.copy(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = state.isKidsProfile,
                kidsAgeLevel = state.kidsAgeLevel.name.takeIf { state.isKidsProfile },
                // Turning kids mode off (reachable only through the parent PIN) drops the PIN.
                parentPin = if (state.isKidsProfile) parentPinToUse else null
            )
            val selectedImage = state.selectedAvatarImageUri
            if (state.useCustomAvatarImage && !selectedImage.isNullOrBlank()) {
                runCatching {
                    profileAvatarImageManager.importAvatar(editing.id, selectedImage)
                }.onSuccess { imported ->
                    updatedProfile = updatedProfile.copy(
                        avatarId = 0,
                        avatarImageVersion = imported.version,
                        avatarImageStoragePath = imported.storagePath
                    )
                }.onFailure {
                    showToast(context.getString(R.string.profile_avatar_import_failed), ToastType.ERROR)
                    return@launch
                }
            } else if (!state.useCustomAvatarImage) {
                profileAvatarImageManager.clearLocalAvatar(editing.id)
                updatedProfile = updatedProfile.copy(
                    avatarImageVersion = 0L,
                    avatarImageStoragePath = null
                )
            }

            profileRepository.updateProfile(updatedProfile)
            val updatedProfiles = _uiState.value.profiles.map {
                if (it.id == updatedProfile.id) updatedProfile else it
            }
            _uiState.value = _uiState.value.copy(
                editingProfile = null,
                profiles = updatedProfiles,
                pendingParentPin = null
            )
            showToast(context.getString(R.string.profile_updated), ToastType.SUCCESS)
            runCatching { cloudSyncRepository.pushToCloud(force = true) }
        }
    }

    // ========== Manage Mode ==========

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = !_uiState.value.isManageMode)
    }

    fun exitManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = false)
    }

    fun deleteProfile(profile: Profile) {
        if (profile.isKidsProfile && !profile.parentPin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = PIN_CONTEXT_PARENT_DELETE
            )
        } else if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "delete"
            )
        } else {
            performDeleteProfile(profile)
        }
    }

    private fun performDeleteProfile(profile: Profile) {
        viewModelScope.launch {
            val activeId = _uiState.value.activeProfile?.id
            profileRepository.deleteProfile(profile.id)
            showToast(context.getString(R.string.profile_deleted), ToastType.SUCCESS)
            if (activeId == profile.id) {
                traktRepository.clearAllProfileCaches()
                watchHistoryRepository.clearProfileCaches()
                watchlistRepository.clearWatchlistCache()
                iptvRepository.invalidateCache()
                profileManager.setCurrentProfileId("default")
                profileManager.setCurrentProfileName("default")
            }
            runCatching { cloudSyncRepository.pushToCloud(force = true) }
        }
    }

    // ========== PIN Management ==========

    fun selectProfileWithLockCheck(profile: Profile) {
        if (profile.isLocked && !profile.pin.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(
                showPinDialog = true,
                pinDialogMode = "verify",
                pendingProfileForPin = profile,
                pinContext = "select"
            )
        } else {
            selectProfile(profile)
        }
    }

    fun showPinSetupDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = true,
            pinDialogMode = "setup"
        )
    }

    fun hidePinDialog() {
        _uiState.value = _uiState.value.copy(
            showPinDialog = false,
            pinDialogMode = "",
            pendingProfileForPin = null,
            pinContext = "",
            pinError = ""
        )
    }

    fun verifyPinAndSelectProfile(enteredPin: String) {
        val contextName = _uiState.value.pinContext
        if (contextName == PIN_CONTEXT_PARENT_VERIFY_FOR_CHANGE) {
            viewModelScope.launch {
                val expected = profileRepository.getParentPin()
                if (PinUtil.verifyPin(enteredPin, expected)) {
                    _uiState.value = _uiState.value.copy(
                        showPinDialog = true,
                        pinDialogMode = "setup",
                        pinContext = PIN_CONTEXT_PARENT_CHANGE,
                        pinError = ""
                    )
                } else {
                    _uiState.value = _uiState.value.copy(pinError = "")
                    _uiState.value = _uiState.value.copy(
                        pinError = context.getString(R.string.profile_pin_invalid)
                    )
                }
            }
            return
        }

        val profile = _uiState.value.pendingProfileForPin ?: return
        val expected = if (contextName.startsWith(PIN_CONTEXT_PARENT_PREFIX)) profile.parentPin else profile.pin
        if (PinUtil.verifyPin(enteredPin, expected)) {
            hidePinDialog()
            // Handle PIN verification context
            when (contextName) {
                "edit", PIN_CONTEXT_PARENT_EDIT -> {
                    // Open edit dialog for locked profile
                    _uiState.value = _uiState.value.copy(
                        editingProfile = profile,
                        newProfileName = profile.name,
                        selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
                        selectedAvatarId = profile.avatarId,
                        selectedAvatarImageUri = null,
                        useCustomAvatarImage = profile.avatarImageVersion > 0L,
                        isKidsProfile = profile.isKidsProfile,
                        kidsAgeLevel = KidsAgeLevel.fromName(profile.kidsAgeLevel),
                        pendingParentPin = null
                    )
                }
                "delete", PIN_CONTEXT_PARENT_DELETE -> {
                    // Delete locked profile after PIN verification
                    performDeleteProfile(profile)
                }
                else -> {
                    // "select": select the locked profile
                    selectProfile(profile)
                }
            }
        } else {
            // PIN incorrect - show error message in dialog
            _uiState.value = _uiState.value.copy(pinError = "")
            _uiState.value = _uiState.value.copy(
                pinError = context.getString(R.string.profile_pin_invalid)
            )
        }
    }

    private fun requestParentPinSetup(context: String) {
        _uiState.value = _uiState.value.copy(
            showPinDialog = true,
            pinDialogMode = "setup",
            pinContext = context
        )
    }

    fun requestParentPinChange() {
        viewModelScope.launch {
            val currentPin = profileRepository.getParentPin()
            if (currentPin.isNullOrEmpty()) {
                _uiState.value = _uiState.value.copy(
                    showPinDialog = true,
                    pinDialogMode = "setup",
                    pinContext = PIN_CONTEXT_PARENT_CHANGE,
                    pinError = ""
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    showPinDialog = true,
                    pinDialogMode = "verify",
                    pinContext = PIN_CONTEXT_PARENT_VERIFY_FOR_CHANGE,
                    pinError = ""
                )
            }
        }
    }

    /** Parent PIN chosen while saving a kids profile or changing parental PIN */
    fun setupParentPin(pin: String) {
        val contextName = _uiState.value.pinContext
        val hashed = PinUtil.hashPin(pin)
        hidePinDialog()
        _uiState.value = _uiState.value.copy(pendingParentPin = hashed)
        viewModelScope.launch {
            profileRepository.setParentPin(hashed)
            when (contextName) {
                PIN_CONTEXT_PARENT_SETUP_CREATE -> createProfile()
                PIN_CONTEXT_PARENT_SETUP_EDIT -> updateProfile()
                PIN_CONTEXT_PARENT_CHANGE -> {
                    showToast(context.getString(R.string.profile_parent_pin_saved), ToastType.SUCCESS)
                }
            }
        }
    }

    fun setupProfilePin(pin: String) {
        val profile = _uiState.value.editingProfile ?: return
        // Hash the PIN before storing
        val hashedPin = PinUtil.hashPin(pin)
        val updatedProfile = profile.copy(pin = hashedPin, isLocked = true)
        viewModelScope.launch {
            profileRepository.updateProfile(updatedProfile)
            val updatedProfiles = _uiState.value.profiles.map {
                if (it.id == updatedProfile.id) updatedProfile else it
            }
            _uiState.value = _uiState.value.copy(
                editingProfile = updatedProfile,
                profiles = updatedProfiles
            )
            hidePinDialog()
            showToast(context.getString(R.string.profile_pin_set_success), ToastType.SUCCESS)
            runCatching { cloudSyncRepository.pushToCloud(force = true) }
        }
    }

    fun removeProfilePin() {
        val profile = _uiState.value.editingProfile ?: return
        val updatedProfile = profile.copy(pin = null, isLocked = false)
        viewModelScope.launch {
            profileRepository.updateProfile(updatedProfile)
            val updatedProfiles = _uiState.value.profiles.map {
                if (it.id == updatedProfile.id) updatedProfile else it
            }
            _uiState.value = _uiState.value.copy(
                editingProfile = updatedProfile,
                profiles = updatedProfiles
            )
            showToast(context.getString(R.string.profile_pin_removed_success), ToastType.SUCCESS)
            runCatching { cloudSyncRepository.pushToCloud(force = true) }
        }
    }

    companion object {
        const val PIN_CONTEXT_PARENT_PREFIX = "parent_"
        const val PIN_CONTEXT_PARENT_EDIT = "parent_edit"
        const val PIN_CONTEXT_PARENT_DELETE = "parent_delete"
        const val PIN_CONTEXT_PARENT_SETUP_CREATE = "parent_setup_create"
        const val PIN_CONTEXT_PARENT_SETUP_EDIT = "parent_setup_edit"
        const val PIN_CONTEXT_PARENT_CHANGE = "parent_change"
        const val PIN_CONTEXT_PARENT_VERIFY_FOR_CHANGE = "parent_verify_for_change"
    }
}
