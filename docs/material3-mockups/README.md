# Material 3 UI/UX mockups — SMS Forwarder

**Branch:** `feature/material3-ui-mockups`  
**Scope:** Graphic design only — **no production Kotlin/Compose code changes**.

## Information architecture (post-onboarding)

Material 3 **bottom Navigation bar** with three destinations:

| Tab | Role |
|-----|------|
| **Status** | Operational state, health, rolling quota, pause / re-enable |
| **Outcomes** | Forwarding log (metadata-only outcomes, filters) |
| **Settings** | View current setup; edit and save |

```
┌─────────────────────────────┐
│ Top app bar (tab title)     │
├─────────────────────────────┤
│                             │
│  Tab content                │
│                             │
├─────────────────────────────┤
│  Status │ Outcomes │ Settings│  ← NavigationBar
└─────────────────────────────┘
```

### Destination change policy (product rule for mocks)

Every **destination number change** must:

1. **Clear** prior destination verification  
2. Require **device re-authentication** (`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`)  
3. Require **re-verification** (new 6-digit code to the **new** number via outbound SIM)  
4. Only then **save** and allow re-enablement  

Line (SIM) changes still pause forwarding and purge unsent jobs (existing product rule).

## How to view

```bash
open docs/material3-mockups/index.html
open docs/material3-mockups/10-status.html   # walk the 3 tabs
open docs/material3-mockups/12-settings.html # edit → reauth → reverify → saved
open docs/material3-mockups/workflow-storyboard.html # workflow + app checkpoints
```

### App captures

The onboarding and operational screens can also be captured as app-only surfaces, without device chrome. The captures use synthetic data only.

Example: `open docs/material3-mockups/10-status.html`

Captured screens:

| Workflow moment | App capture |
|---|---|
| Disclosure | [01-disclosure.png](assets/app/01-disclosure.png) |
| Permissions and health | [02-permissions.png](assets/app/02-permissions.png) |
| Inbound SIM | [03-inbound-sim.png](assets/app/03-inbound-sim.png) |
| Destination verification | [04-destination.png](assets/app/04-destination.png) |
| Status notification | [06-notification.png](assets/app/06-notification.png) |
| Enabled status | [10-status.png](assets/app/10-status.png) |
| Metadata-only outcomes | [11-outcomes.png](assets/app/11-outcomes.png) |

## Design direction

| Token area | Choice |
|------------|--------|
| **Seed** | Indigo-violet (`#4F46E5`) |
| **Surfaces** | M3 surface container ladder |
| **Nav** | M3 NavigationBar (active pill icon + label) |
| **Components** | Filled / tonal / outlined buttons, cards, chips, top app bar |

## Screen inventory

### Main shell (NavigationBar)

| File | Screen |
|------|--------|
| `10-status.html` | Status tab |
| `11-outcomes.html` | Outcomes / forwarding log |
| `12-settings.html` | Settings — current setup |
| `12b-settings-edit.html` | Edit inbound / outbound / destination |
| `12c-settings-reauth.html` | Destination change — authenticate |
| `12d-settings-reverify.html` | Destination change — re-verify code |
| `12e-settings-saved.html` | Setup saved confirmation |

### Onboarding & other

| File | Screen |
|------|--------|
| `01`–`04` | Disclosure, permissions, inbound SIM, destination |
| `05` / `05-dashboard-dark` | Legacy single dashboard (superseded by tabs) |
| `06-notification.html` | Ongoing status notification |
| `index.html` | Gallery |
| `m3.css` / `partials-nav.js` | Tokens + shared nav injection |

## Out of scope (this branch)

- Changing `app/` Compose sources  
- Real Compose `NavigationBar` implementation  

## Next (implementation branch)

1. Replace single dashboard root with 3-destination `NavigationBar`.  
2. Split outcomes out of status.  
3. Settings repository UX + gate destination edits: auth → verify → save.  
4. Map M3 tokens into Compose theme.
