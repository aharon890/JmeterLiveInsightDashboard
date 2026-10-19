# Dark Mode & Accessibility

## Dark Mode

Toggle via the **crescent/sun icon** in the header.

Screenshot: Dashboard in dark mode showing dark backgrounds with light text.

### What Changes

| Element | Light to Dark |
|---------|--------------|
| **Background** | White to dark grey |
| **Text** | Dark grey to light grey/white |
| **Cards** | White cards to dark cards |
| **Tables** | White rows to dark rows with subtle borders |
| **Charts** | Light canvas to dark canvas with adapted axis colors and grid lines |
| **Badges** | Standard colors to dark-appropriate tones |
| **Buttons** | Light background to dark background with light text |
| **Filter bar** | Light background to dark background |
| **Footer** | Light background to dark background |
| **Tooltips** | Unchanged: the Summary and Timeline chart tooltips are dark in both modes, the other charts' tooltips light |

### Behavior

- **Default:** OFF (light theme)
- **Toggle:** Click icon — CSS variables switch to dark palette
- **Icon changes:** Crescent (light mode) to sun (dark mode)
- **Charts reinitialize:** ECharts instances rebuild with dark-appropriate colors
- **Persistence:** Saved to localStorage; survives page reload

## Colorblind Mode

Toggle via the **eye icon** in the header.

Screenshot: Colorblind mode showing blue/orange instead of green/red.

### What Changes

Swaps all semantic green/red colors to blue/orange:

| Element | Standard | Colorblind |
|---------|----------|------------|
| **PASS badge** | Green | Blue |
| **FAIL badge** | Red | Orange |
| **WARN badge** | Amber | Amber (unchanged) |
| **Error cards** | Red tones | Orange tones |
| **Error code badges** | Red tones | Orange tones |
| **Trend arrows (good)** | Green | Blue |
| **Trend arrows (bad)** | Red | Orange |

### Behavior

- **Default:** OFF
- **Toggle:** Click icon — body gets `cb-mode` CSS class
- **Icon:** looks the same whether the mode is on or off; the badge, card and trend colors show that it is on
- **Persistence:** Saved to localStorage

### Chart Line Colors

Colorblind mode does **not** affect chart line palettes. For colorblind-friendly chart colors, use the **Okabe-Ito** palette in the [Filter Bar](06-Filter-Bar.md).

## Combined Modes

Dark mode and colorblind mode work independently — all 4 combinations are supported:

| Mode | CSS Classes |
|------|-------------|
| Light + Standard | (none) |
| Light + Colorblind | `cb-mode` |
| Dark + Standard | `dark-mode` |
| Dark + Colorblind | `dark-mode cb-mode` |

## Color Palettes

Chart line colors are controlled separately from semantic colors:

| Palette | Best For |
|---------|----------|
| **Default** | General use |
| **Okabe-Ito** | Colorblind users (deuteranopia/protanopia safe) |
| **High Contrast** | Presentations, printed screenshots |

Palette selection is in the [Filter Bar](06-Filter-Bar.md).

## Recommendations

| Audience | Suggested Settings |
|----------|-------------------|
| Screen viewing | Default palette, Medium lines |
| Colorblind users | Okabe-Ito palette + Colorblind mode ON |
| Presentations (projector) | High Contrast palette, Thick lines, Dark mode |
| Screen sharing | Dark mode, Thick lines |

## Snapshot Export

When exporting a [snapshot HTML](09-Export-Snapshot.md), the current dark mode setting is preserved. The exported file opens in the same theme.

## Tips

- Dark mode reduces eye strain during long test monitoring sessions.
- The colorblind toggle changes semantic colors (pass/fail) only — combine it with the Okabe-Ito palette for full colorblind support.
- All 4 mode combinations are tested and render correctly.
