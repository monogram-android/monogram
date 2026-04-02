# Monogram Android App - Material 3 Expressive Upgrade Inventory
## Comprehensive Presentation Layer UI Patterns Analysis

**Total Kotlin Files:** 362  
**Analysis Date:** April 3, 2026  
**Focus:** Material 3 Expressive API upgrade opportunities

---

## EXECUTIVE SUMMARY

The Monogram Android app has good Material 3 adoption with 362 Kotlin files in the presentation layer. The codebase uses Material 3 components (Card, Switch, TopAppBar) consistently but does not yet leverage the **ExperimentalMaterial3ExpressiveApi** for enhanced animations and shape customization.

### Key Metrics:
- Navigation patterns: 2 files
- Chip implementations: 35+ instances across 10+ files  
- Card implementations: 43+ instances across 20+ files
- TextField implementations: 126+ instances across 30+ files
- Switch/Checkbox: 124+ instances across 20+ files
- Modal bottom sheets: 79+ instances across 35+ files
- TopAppBar variants: 86+ instances across 40+ files
- Dialog components: 79+ instances across 40+ files
- Date/Time pickers: 83+ instances across 5+ files
- Tab implementations: 4+ instances

---

## 1. NAVIGATION PATTERNS

### NavigationBar Usage (2 files)
```
Z:\GitHub\monogram\presentation\src\main\java\org\monogram\presentation\features\chats\currentChat\editor\video\VideoEditorScreen.kt:346
Z:\GitHub\monogram\presentation\src\main\java\org\monogram\presentation\features\chats\currentChat\editor\photo\PhotoEditorScreen.kt:226
```
**Pattern:** Transparent container with tool-based selection (video/photo editor tool switching)  
**Upgrade:** Add Material 3 Expressive animations for tab transitions

### App Root Navigation
```
Z:\GitHub\monogram\presentation\src\main\java\org\monogram\presentation\root\DefaultRootComponent.kt
```
**Pattern:** Uses Decompose StackNavigation library (not Jetpack Navigation)  
**Notes:** No BottomNavigation or NavigationRail in main app shell

---

## 2. TOOLTIP PATTERNS

**Status:** ZERO implementations found  
**Files Searched:** 362 Kotlin files  
**Opportunity:** Add Rich Tooltips to frequently-used buttons

---

## 3. CHIP PATTERNS (35+ instances)

### FilterChip Usage (5 files)
```
1. GalleryFilterComponents.kt:118 - Gallery bucket filtering
2. StatisticsViewer.kt:1204 - Graph series selection
3. ChatThemeEditorScreen.kt:274,280 - Theme color selection
4. NewChatContent.kt:437 - Chat type filtering
5. ProfileLogsContent.kt:315 - Action log filtering (via FilterChipCompact)
```

### AssistChip Usage (1 file)
```
StatisticsViewer.kt:1494 - Status/permission indicator
```

### Custom Chip Components (5 implementations)
```
1. FilterChipCompact (FilterChipCompact.kt:19)
   - Custom radio button style with icons
   - Usage: Profile logs filtering
   
2. CompactInsightChip (StatisticsViewer.kt:514)
   - Custom metric display chip
   - Usage: Statistics view
   
3. PermissionChip (ActionDetails.kt:365)
   - Custom permission change display
   - Usage: Profile logs permission tracking
   
4. ColorInfoChip (ChatThemeEditorScreen.kt:520)
   - Custom color info display
   - Usage: Chat theme editor
   
5. UsernameChip (UsernamesTile.kt:156, ProfileSections.kt:1558)
   - Custom username display chip
   - Usage: Profile username display
```

### Upgrade Priority: HIGH
- Convert FilterChipCompact to Material 3 Expressive FilterChip
- Add InputChip for tag-like selections
- Implement chip drag-and-drop in reorderable contexts

---

## 4. SEGMENTED BUTTON PATTERNS

**Status:** ZERO implementations found  
**Opportunity:** Replace FilterChip groups with SegmentedButton for better multi-select UX

---

## 5. CARD VARIANTS (43+ instances)

### Standard Card Implementation
```
SettingsGroup (SettingsGroup.kt:17)
- Container: surfaceContainer
- Shape: RoundedCornerShape(24.dp)
- Elevation: 0.dp
- Usage: 20+ settings screens
```

### Custom Card Components (7 implementations)
```
1. StatCard (StatisticsViewer.kt:826)
   - Primary-based coloring
   - Usage: Statistics display
   
2. RevenueMetricCard (StatisticsViewer.kt:742)
   - Custom revenue display
   
3. PresetCard (ChatThemeEditorScreen.kt:464)
   - Theme preset with buttons
   - Shape: RoundedCornerShape(14.dp)
   
4. SelectedCountCard (GalleryStatusComponents.kt:31)
   - Selection counter display
   
5. PermissionCard (GalleryStatusComponents.kt:80)
   - Permission request display
   
6. ActiveAccountCard (AccountMenu.kt:528)
   - Account display in menu
   
7. PremiumStatusCard (PremiumContent.kt:184)
   - Premium tier indicator
```

### Card Files with Opportunities
```
InstantViewer.kt:641,831,888 - Could use ElevatedCard/OutlinedCard for hierarchy
ProfileTopBar.kt:136,152 - Could use OutlinedCard for secondary content
ChatListContent.kt:510,525 - Could differentiate with card variants
```

### Upgrade Priority: HIGH
- Implement ElevatedCard for important content
- Use OutlinedCard for secondary/alternative content
- Add Material 3 Expressive shape variants

---

## 6. TEXT INPUT PATTERNS (126+ instances)

### Custom SettingsTextField Component
```
ChatCreationCommon.kt:39-125
- Features:
  * Surface-wrapped TextField with custom background
  * Icon support (leading icon)
  * Custom shapes per ItemPosition (TOP/MIDDLE/BOTTOM/STANDALONE)
  * Rounded corner variants: 24.dp root, 4.dp middle
  * 30+ instances across settings screens
```

### TextField Usage Distribution
```
- Emoji/Sticker search: StickersGrid.kt:344, GifsGrid.kt:243, EmojisGrid.kt:416
- User search: UserSelectionContent.kt:41
- Profile bio: EditProfileContent.kt:702
- Password input: PasscodeContent.kt:142
- Hex color input: ChatThemeEditorScreen.kt:337,612
- Chat search: ChatTopBar.kt:89
- Privacy search: PrivacyListContent.kt:366
- Text editor: InputTextField.kt:68+ (rich text with formatting)
```

### Specialized TextFields
```
InputTextField.kt:68+
- Rich text editor with markdown support
- Used in message composition
- Handles text formatting, URLs, code blocks
```

### Upgrade Priority: HIGH
- Enhance SettingsTextField with Material 3 TextField state support
- Add supporting text for errors/hints
- Implement Material 3 Expressive input decoration transitions

---

## 7. SWITCH & CHECKBOX PATTERNS (124+ instances)

### Custom SettingsSwitchTile Component
```
SettingsSwitchTile.kt:20-116
- Features:
  * Surface background (surfaceContainer)
  * Icon with colored background circle (15% opacity)
  * Subtitle text support
  * Custom shapes per ItemPosition (24.dp root, 4.dp middle)
  * 50+ instances across app
  
- Color Defaults:
  * checkedThumbColor: onPrimary
  * checkedTrackColor: primary
  * uncheckedThumbColor: outline
  * uncheckedTrackColor: surfaceContainerHighest
```

### Switch Usage Locations
```
1. ChatSettingsContent.kt:616+ (11 instances)
   - Silent hours, auto-delete, message forwarding toggles
   
2. NotificationsContent.kt:85+ (11 instances)
   - Notification preference toggles
   
3. AdminManageContent.kt:285 (PermissionSwitch wrapper)
   - Chat member permission toggles (15+ instances)
   
4. ProfileSections.kt:898 (SettingsSwitchTile)
   - Admin/restriction toggles
   
5. StorageUsageContent.kt:189
   - Cache clearing toggle
   
6. EditProfileContent.kt:202 (Direct Switch, not wrapper)
   - Profile visibility toggles
```

### Checkbox Status
**ZERO Checkbox components found** - App exclusively uses Switches

### Upgrade Priority: MEDIUM
- Add Material 3 Expressive switch animations
- Implement haptic feedback on toggle
- Add switch state transition improvements

---

## 8. MODAL BOTTOM SHEET PATTERNS (79 instances across 35+ files)

### Core Bottom Sheet Usage
```
Total Instances: 79
Total Files: 35+
Pattern: rememberModalBottomSheetState(skipPartiallyExpanded = true)
Scrim: Default (instant black overlay, no animation)
```

### Key Files by Sheet Count
```
1. ProfileSections.kt: 4 sheets
   - ProfileQRDialog (1104)
   - ProfileReportDialog (1202)
   - ProfilePermissionsDialog (1279)
   - ProfileTOSDialog (1353)

2. SettingsContent.kt: 3 sheets
   - Theme customization (226)
   - Language selection (310)
   - Debug settings (366)

3. MiniAppDialogs.kt: 6 sheets
   - Closing confirmation (26)
   - Popup dialog (83)
  
