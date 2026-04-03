# Task ID: 13

**Title:** Branding and aesthetics

**Status:** pending

**Dependencies:** 1

**Priority:** medium

**Description:** Cohesive visual identity across launcher, theme, and key screens.

**Details:**

Define a small design system: primary/secondary colors, typography, shapes, and spacing consistent with Material 3. Add adaptive launcher icon and themed splash (SplashScreen API). Polish auth and home layouts (elevation, margins, empty states). Optional: illustration or wordmark for empty home.

**Test Strategy:**

Visual review on light/dark (if supported) and one small phone + one large; verify icon and splash on cold start.

## Subtasks

### 13.1. Theme: colors and typography

**Status:** pending  
**Dependencies:** None  

Centralize brand colors and type scale in themes.xml / Material theme.

**Details:**

Override colorPrimary, colorSecondary, surfaces; choose font family or stick to M3 defaults with tuned colors.

### 13.2. Adaptive launcher icon

**Status:** pending  
**Dependencies:** None  

Replace default mipmap with branded foreground + optional background.

**Details:**

Use Android Studio Image Asset wizard; support API 26+ adaptive layers.

### 13.3. Splash screen

**Status:** pending  
**Dependencies:** 13.1, 13.2  

Themed splash on cold start via SplashScreen API.

**Details:**

Install androidx.core:core-splashscreen; set windowSplashScreenAnimatedIcon and background; keep duration short.

### 13.4. Polish auth screens

**Status:** pending  
**Dependencies:** 13.1  

Align signup/login with theme and spacing hierarchy.

**Details:**

Refine padding, button styles, and status message placement for a finished look.

### 13.5. In-app screens consistency

**Status:** pending  
**Dependencies:** 13.1, 13.4  

Home and future feature UIs use the same app bar, cards, and spacing.

**Details:**

Extract shared styles or composables as the app grows; document spacing tokens (8dp grid).
