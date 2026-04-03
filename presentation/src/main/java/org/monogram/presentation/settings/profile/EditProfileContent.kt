@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.settings.profile

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.maplibre.compose.MapView
import com.maplibre.compose.camera.CameraState
import com.maplibre.compose.camera.MapViewCamera
import com.maplibre.compose.rememberSaveableMapViewCamera
import com.maplibre.compose.symbols.Symbol
import kotlinx.coroutines.delay
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.monogram.domain.models.BirthdateModel
import org.monogram.domain.models.BusinessOpeningHoursIntervalModel
import org.monogram.domain.models.BusinessOpeningHoursModel
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar
import org.monogram.presentation.core.ui.ItemPosition
import org.monogram.presentation.core.util.FileUtils
import org.monogram.presentation.features.chats.chatList.components.SectionHeader
import org.monogram.presentation.features.chats.chatList.components.SettingsTextField
import java.util.*

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditProfileContent(component: EditProfileComponent) {
    val state by component.state.subscribeAsState()
    val context = LocalContext.current

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            uri?.let {
                val path = FileUtils.getPath(context, it)
                component.onChangeAvatar(path.toString())
            }
        }
    )

    val mapOptions = remember {
        MapLibreMapOptions.createFromAttributes(context, null).apply {
            scrollGesturesEnabled(true)
            zoomGesturesEnabled(true)
            tiltGesturesEnabled(true)
            rotateGesturesEnabled(true)
            doubleTapGesturesEnabled(true)
            textureMode(true)
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showWorkHoursSheet by remember { mutableStateOf(false) }
    var showGeoDialog by remember { mutableStateOf(false) }
    var showUsernamePicker by remember { mutableStateOf(false) }

    if (showUsernamePicker) {
        val activeUsernames = state.user?.usernames?.activeUsernames ?: emptyList()
        val disabledUsernames = state.user?.usernames?.disabledUsernames ?: emptyList()
        val collectibleUsernames = state.user?.usernames?.collectibleUsernames ?: emptyList()

        var retryAfterSeconds by remember { mutableIntStateOf(0) }

        LaunchedEffect(retryAfterSeconds) {
            if (retryAfterSeconds > 0) {
                while (retryAfterSeconds > 0) {
                    delay(1000)
                    retryAfterSeconds--
                }
            }
        }

        LaunchedEffect(state.error) {
            if (state.error?.contains("retry after", ignoreCase = true) == true) {
                val seconds = state.error?.filter { it.isDigit() }?.toIntOrNull() ?: 0
                if (seconds > 0) {
                    retryAfterSeconds = seconds
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showUsernamePicker = false },
            dragHandle = { BottomSheetDefaults.DragHandle() },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.usernames_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    if (retryAfterSeconds > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.retry_after_format, retryAfterSeconds),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (activeUsernames.isNotEmpty()) {
                        item(key = "active_header") {
                            SectionHeader(
                                stringResource(R.string.active_usernames_title),
                                modifier = Modifier
                                    .animateItem()
                                    .padding(start = 4.dp)
                            )
                        }
                        itemsIndexed(activeUsernames, key = { index, username -> "active_${index}_$username" }) { _, username ->
                            ListItem(
                                headlineContent = { Text("@$username") },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (activeUsernames.size > 1) {
                                            IconButton(onClick = {
                                                val newList = activeUsernames.toMutableList()
                                                val index = newList.indexOf(username)
                                                if (index > 0) {
                                                    Collections.swap(newList, index, index - 1)
                                                    component.onReorderUsernames(newList)
                                                }
                                            },
                                                enabled = activeUsernames.indexOf(username) > 0 && retryAfterSeconds == 0
                                            ) {
                                                Icon(Icons.Rounded.KeyboardArrowUp, null)
                                            }
                                            IconButton(onClick = {
                                                val newList = activeUsernames.toMutableList()
                                                val index = newList.indexOf(username)
                                                if (index < newList.size - 1) {
                                                    Collections.swap(newList, index, index + 1)
                                                    component.onReorderUsernames(newList)
                                                }
                                            },
                                                enabled = activeUsernames.indexOf(username) < activeUsernames.size - 1 && retryAfterSeconds == 0
                                            ) {
                                                Icon(Icons.Rounded.KeyboardArrowDown, null)
                                            }
                                        }
                                        Switch(
                                            checked = true,
                                            onCheckedChange = { component.onToggleUsername(username, false) },
                                            enabled = retryAfterSeconds == 0
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier
                                    .animateItem()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable(enabled = retryAfterSeconds == 0) {
                                        val newList = activeUsernames.toMutableList()
                                        newList.remove(username)
                                        newList.add(0, username)
                                        component.onReorderUsernames(newList)
                                    }
                            )
                        }
                    }

                    if (disabledUsernames.isNotEmpty()) {
                        item(key = "disabled_header") {
                            SectionHeader(
                                stringResource(R.string.disabled_usernames_title),
                                modifier = Modifier
                                    .animateItem()
                                    .padding(start = 4.dp)
                            )
                        }
                        itemsIndexed(disabledUsernames, key = { index, username -> "disabled_${index}_$username" }) { _, username ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "@$username",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = false,
                                        onCheckedChange = { component.onToggleUsername(username, true) },
                                        enabled = retryAfterSeconds == 0
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier
                                    .animateItem()
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }

                    if (collectibleUsernames.isNotEmpty()) {
                        item(key = "collectible_header") {
                            SectionHeader(
                                stringResource(R.string.collectible_usernames_title),
                                modifier = Modifier
                                    .animateItem()
                                    .padding(start = 4.dp)
                            )
                        }
                        itemsIndexed(collectibleUsernames, key = { index, username -> "collectible_${index}_$username" }) { _, username ->
                            val isActive = activeUsernames.contains(username)
                            ListItem(
                                headlineContent = { Text("@$username") },
                                trailingContent = {
                                    Switch(
                                        checked = isActive,
                                        onCheckedChange = { component.onToggleUsername(username, it) },
                                        enabled = retryAfterSeconds == 0
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier
                                    .animateItem()
                                    .clip(RoundedCornerShape(16.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { showUsernamePicker = false },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.done_button), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.birthdate?.let {
                Calendar.getInstance().apply {
                    set(it.year ?: 2000, it.month - 1, it.day)
                }.timeInMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        val cal = Calendar.getInstance().apply { timeInMillis = it }
                        component.onUpdateBirthdate(
                            BirthdateModel(
                                day = cal.get(Calendar.DAY_OF_MONTH),
                                month = cal.get(Calendar.MONTH) + 1,
                                year = cal.get(Calendar.YEAR)
                            )
                        )
                    }
                    showDatePicker = false
                }) {
                    Text(stringResource(R.string.ok_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel_button))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showWorkHoursSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWorkHoursSheet = false },
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            val initialIntervals = state.businessOpeningHours?.intervals ?: emptyList()
            val initialDays = initialIntervals.map { (it.startMinute / 1440) + 1 }.toSet()
            val firstInterval = initialIntervals.firstOrNull()

            var selectedDays by remember {
                mutableStateOf(if (initialDays.isEmpty()) setOf(1, 2, 3, 4, 5) else initialDays)
            }
            var startMinuteOfDay by remember {
                mutableStateOf(firstInterval?.let { it.startMinute % 1440 } ?: (9 * 60))
            }
            var endMinuteOfDay by remember {
                mutableStateOf(firstInterval?.let { it.endMinute % 1440 } ?: (18 * 60))
            }

            var showStartTimePicker by remember { mutableStateOf(false) }
            var showEndTimePicker by remember { mutableStateOf(false) }

            if (showStartTimePicker) {
                val timePickerState = rememberTimePickerState(
                    initialHour = startMinuteOfDay / 60,
                    initialMinute = startMinuteOfDay % 60
                )
                Dialog(onDismissRequest = { showStartTimePicker = false }) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.select_start_time),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            TimePicker(state = timePickerState)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    showStartTimePicker = false
                                }) { Text(stringResource(R.string.cancel_button)) }
                                TextButton(onClick = {
                                    startMinuteOfDay = timePickerState.hour * 60 + timePickerState.minute
                                    showStartTimePicker = false
                                }) { Text(stringResource(R.string.ok_button)) }
                            }
                        }
                    }
                }
            }

            if (showEndTimePicker) {
                val timePickerState = rememberTimePickerState(
                    initialHour = endMinuteOfDay / 60,
                    initialMinute = endMinuteOfDay % 60
                )
                Dialog(onDismissRequest = { showEndTimePicker = false }) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.select_end_time),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            TimePicker(state = timePickerState)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    showEndTimePicker = false
                                }) { Text(stringResource(R.string.cancel_button)) }
                                TextButton(onClick = {
                                    endMinuteOfDay = timePickerState.hour * 60 + timePickerState.minute
                                    showEndTimePicker = false
                                }) { Text(stringResource(R.string.ok_button)) }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp)
            ) {
                Text(
                    text = stringResource(R.string.work_hours_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.working_days_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val days = listOf(
                        stringResource(R.string.monday_short),
                        stringResource(R.string.tuesday_short),
                        stringResource(R.string.wednesday_short),
                        stringResource(R.string.thursday_short),
                        stringResource(R.string.friday_short),
                        stringResource(R.string.saturday_short),
                        stringResource(R.string.sunday_short)
                    )
                    days.forEachIndexed { index, day ->
                        val dayNum = index + 1
                        val isSelected = selectedDays.contains(dayNum)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    selectedDays = if (isSelected) selectedDays - dayNum else selectedDays + dayNum
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = day,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.time_range_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.from_label), style = MaterialTheme.typography.labelSmall)
                            Text(
                                String.format("%02d:%02d", startMinuteOfDay / 60, startMinuteOfDay % 60),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                    Text("-", modifier = Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold)
                    Surface(
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.to_label), style = MaterialTheme.typography.labelSmall)
                            Text(
                                String.format("%02d:%02d", endMinuteOfDay / 60, endMinuteOfDay % 60),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = {
                        val intervals = selectedDays.map { day ->
                            val offset = (day - 1) * 1440
                            BusinessOpeningHoursIntervalModel(
                                startMinute = offset + startMinuteOfDay,
                                endMinute = offset + endMinuteOfDay
                            )
                        }.sortedBy { it.startMinute }

                        component.onUpdateBusinessOpeningHours(
                            BusinessOpeningHoursModel(
                                timeZoneId = TimeZone.getDefault().id,
                                intervals = intervals
                            )
                        )
                        showWorkHoursSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }

    if (showGeoDialog) {
        Dialog(
            onDismissRequest = { showGeoDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var selectedLatitude by remember {
                mutableStateOf(
                    if (state.businessLatitude != 0.0 || state.businessLongitude != 0.0) {
                        state.businessLatitude
                    } else {
                        51.505
                    }
                )
            }
            var selectedLongitude by remember {
                mutableStateOf(
                    if (state.businessLatitude != 0.0 || state.businessLongitude != 0.0) {
                        state.businessLongitude
                    } else {
                        -0.09
                    }
                )
            }

            val camera = rememberSaveableMapViewCamera(
                MapViewCamera(
                    CameraState.Centered(
                        selectedLatitude,
                        selectedLongitude,
                    ),
                )
            )

            val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                ) {
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { location ->
                            location?.let {
                                selectedLatitude = it.latitude
                                selectedLongitude = it.longitude
                                component.onReverseGeocode(it.latitude, it.longitude)
                            }
                        }
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.business_location_title),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { showGeoDialog = false }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                                        .addOnSuccessListener { location ->
                                            location?.let {
                                                selectedLatitude = it.latitude
                                                selectedLongitude = it.longitude
                                                component.onReverseGeocode(it.latitude, it.longitude)
                                            }
                                        }
                                } else {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            }) {
                                Icon(Icons.Rounded.MyLocation, contentDescription = "My Location")
                            }
                        }
                    )
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    MapView(
                        modifier = Modifier
                            .fillMaxWidth(),
                        camera = camera,
                        styleUrl = MAP_STYLE,
                        mapOptions = mapOptions,
                        onTapGestureCallback = { coordinate ->
                            selectedLatitude = coordinate.coordinate.latitude
                            selectedLongitude = coordinate.coordinate.longitude
                            component.onReverseGeocode(coordinate.coordinate.latitude, coordinate.coordinate.longitude)
                        }
                    ) {
                        Symbol(
                            center = LatLng(selectedLatitude, selectedLongitude),
                            imageId = R.drawable.ic_map_marker,
                            size = 2f
                        )
                    }

                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )

                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .navigationBarsPadding(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            OutlinedTextField(
                                value = state.businessAddress,
                                onValueChange = {
                                    component.onUpdateBusinessAddress(
                                        it,
                                        selectedLatitude,
                                        selectedLongitude
                                    )
                                },
                                label = { Text(stringResource(R.string.address_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.LocationOn,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            )

                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    component.onUpdateBusinessAddress(
                                        state.businessAddress,
                                        selectedLatitude,
                                        selectedLongitude
                                    )
                                    showGeoDialog = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                Text(
                                    stringResource(R.string.confirm_location_button),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.semantics { contentDescription = "EditProfileContent" },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.edit_profile_title),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                },
                actions = {
                    if (state.isLoading) {
                        LoadingIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 16.dp),
                        )
                    } else if (state.user != null) {
                        IconButton(onClick = component::onSave) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.action_save),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.user == null && state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                ContainedLoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 100.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Avatar(
                                path = state.avatarPath,
                                name = state.firstName,
                                size = 100.dp,
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.CameraAlt,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    SettingsTextField(
                        value = state.firstName,
                        onValueChange = component::onUpdateFirstName,
                        placeholder = stringResource(R.string.first_name_placeholder),
                        icon = Icons.Rounded.Person,
                        position = ItemPosition.TOP
                    )
                    SettingsTextField(
                        value = state.lastName,
                        onValueChange = component::onUpdateLastName,
                        placeholder = stringResource(R.string.last_name_placeholder),
                        icon = Icons.Rounded.PersonOutline,
                        position = ItemPosition.MIDDLE
                    )
                    SettingsTextField(
                        value = state.bio,
                        onValueChange = component::onUpdateBio,
                        placeholder = stringResource(R.string.bio_placeholder),
                        icon = Icons.Rounded.Info,
                        position = ItemPosition.BOTTOM,
                        singleLine = false
                    )

                    Text(
                        text = stringResource(R.string.bio_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.username_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                    )

                    val hasMultipleUsernames = state.user?.usernames?.let {
                        it.activeUsernames.size + it.disabledUsernames.size + it.collectibleUsernames.size > 1
                    } ?: false

                    SettingsTextField(
                        value = state.username,
                        onValueChange = component::onUpdateUsername,
                        placeholder = stringResource(R.string.username_label),
                        icon = Icons.Rounded.AlternateEmail,
                        position = ItemPosition.STANDALONE,
                        trailingIcon = if (hasMultipleUsernames) {
                            {
                                IconButton(onClick = { showUsernamePicker = true }) {
                                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                                }
                            }
                        } else null
                    )
                    Text(
                        text = stringResource(R.string.username_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.birthday_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                    )

                    val birthdateText = state.birthdate?.let { "${it.day}/${it.month}/${it.year ?: ""}" }
                        ?: stringResource(R.string.not_set)
                    Box(modifier = Modifier.clickable { showDatePicker = true }) {
                        SettingsTextField(
                            value = birthdateText,
                            onValueChange = { },
                            placeholder = stringResource(R.string.birthday_placeholder),
                            icon = Icons.Rounded.Cake,
                            position = ItemPosition.STANDALONE,
                            enabled = false
                        )
                    }
                }

                if (state.user?.isPremium == true) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.telegram_business_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            SettingsTextField(
                                value = if (state.personalChatId != 0L) state.personalChatId.toString() else "",
                                onValueChange = {
                                    it.toLongOrNull()?.let { id -> component.onUpdatePersonalChatId(id) }
                                },
                                placeholder = stringResource(R.string.linked_channel_id_placeholder),
                                icon = Icons.Rounded.Link,
                                position = ItemPosition.TOP
                            )

                            state.linkedChat?.let { chat ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(
                                        path = chat.avatarPath,
                                        fallbackPath = chat.personalAvatarPath,
                                        name = chat.title,
                                        size = 40.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = chat.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        chat.username?.let {
                                            Text(
                                                text = "@$it",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                        }

                        Spacer(Modifier.height(2.dp))

                        SettingsTextField(
                            value = state.businessBio,
                            onValueChange = component::onUpdateBusinessBio,
                            placeholder = stringResource(R.string.business_bio_placeholder),
                            icon = Icons.Rounded.Business,
                            position = ItemPosition.MIDDLE
                        )

                        SettingsTextField(
                            value = state.businessAddress,
                            onValueChange = {
                                component.onUpdateBusinessAddress(
                                    it,
                                    state.businessLatitude,
                                    state.businessLongitude
                                )
                            },
                            placeholder = stringResource(R.string.business_address_placeholder),
                            icon = Icons.Rounded.LocationOn,
                            position = ItemPosition.MIDDLE
                        )

                        val geoText = if (state.businessLatitude != 0.0 || state.businessLongitude != 0.0) {
                            "${String.format("%.4f", state.businessLatitude)}, ${
                                String.format(
                                    "%.4f",
                                    state.businessLongitude
                                )
                            }"
                        } else stringResource(R.string.not_set)

                        Box(modifier = Modifier.clickable { showGeoDialog = true }) {
                            SettingsTextField(
                                value = geoText,
                                onValueChange = { },
                                placeholder = stringResource(R.string.location_geo_placeholder),
                                icon = Icons.Rounded.Map,
                                position = ItemPosition.MIDDLE,
                                enabled = false
                            )
                        }

                        Spacer(Modifier.height(2.dp))

                        val hoursText = state.businessOpeningHours?.intervals?.firstOrNull()?.let {
                            val startH = (it.startMinute % 1440) / 60
                            val startM = (it.startMinute % 1440) % 60
                            val endH = (it.endMinute % 1440) / 60
                            val endM = (it.endMinute % 1440) % 60
                            val daysCount = state.businessOpeningHours?.intervals?.size ?: 0
                            String.format(
                                "%02d:%02d - %02d:%02d %s",
                                startH,
                                startM,
                                endH,
                                endM,
                                stringResource(R.string.days_count_format, daysCount)
                            )
                        } ?: stringResource(R.string.not_set)

                        Box(modifier = Modifier.clickable { showWorkHoursSheet = true }) {
                            SettingsTextField(
                                value = hoursText,
                                onValueChange = { },
                                placeholder = stringResource(R.string.opening_hours_placeholder),
                                icon = Icons.Rounded.Schedule,
                                position = ItemPosition.BOTTOM,
                                enabled = false
                            )
                        }

                        Text(
                            text = stringResource(R.string.premium_business_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}
