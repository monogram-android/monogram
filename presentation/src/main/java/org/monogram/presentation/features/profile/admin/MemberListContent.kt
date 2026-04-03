@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.monogram.presentation.features.profile.admin

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import org.monogram.presentation.R
import org.monogram.presentation.core.ui.Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberListContent(component: MemberListComponent) {
    val state by component.state.subscribeAsState()
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.isSearchActive) {
        if (state.isSearchActive) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    AnimatedContent(
                        targetState = state.isSearchActive,
                        transitionSpec = {
                            fadeIn() + slideInHorizontally { it / 2 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 2 }
                        },
                        label = "TitleSearch"
                    ) { isSearch ->
                        if (isSearch) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (state.searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.search_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                BasicTextField(
                                    value = state.searchQuery,
                                    onValueChange = component::onSearch,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester),
                                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                                )
                            }
                        } else {
                            Text(
                                text = when (state.type) {
                                    MemberListComponent.MemberListType.ADMINS -> stringResource(R.string.administrators)
                                    MemberListComponent.MemberListType.MEMBERS -> stringResource(R.string.subscribers)
                                    MemberListComponent.MemberListType.BLACKLIST -> stringResource(R.string.blacklist)
                                },
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (state.isSearchActive) {
                        IconButton(onClick = { component.onSearch("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.clear))
                        }
                    } else {
                        IconButton(onClick = component::onToggleSearch) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.search))
                        }
                        if (state.type != MemberListComponent.MemberListType.BLACKLIST) {
                            IconButton(onClick = component::onAddMember) {
                                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add))
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading && state.members.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ContainedLoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.members, key = { it.user.id }) { member ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    listOfNotNull(member.user.firstName, member.user.lastName).joinToString(" "),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            },
                            supportingContent = {
                                member.rank?.let {
                                    if (it.isNotEmpty()) {
                                        Text(
                                            text = it,
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                Avatar(
                                    path = member.user.avatarPath,
                                    fallbackPath = member.user.personalAvatarPath,
                                    name = member.user.firstName,
                                    size = 48.dp
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { component.onMemberClick(member) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = stringResource(R.string.edit),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            colors = ListItemDefaults.colors(
                                containerColor = Color.Transparent
                            ),
                            modifier = Modifier.clickable { component.onMemberClick(member) }
                        )
                    }
                }
                if (state.canLoadMore) {
                    item {
                        LaunchedEffect(Unit) { component.onLoadMore() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp), contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                if (state.members.isEmpty() && !state.isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxSize()
                                .padding(bottom = 100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.isSearchActive) stringResource(R.string.no_results_found) else stringResource(R.string.no_members_yet),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
