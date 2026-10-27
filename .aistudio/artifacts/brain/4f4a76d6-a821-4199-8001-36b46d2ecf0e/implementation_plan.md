# ReadMe AI: Gemini Live Voice Conversations & Document Companion

Integrating real-time conversational AI into ReadMe using the Gemini Live API (`gemini-3.8-flash` / Live API), allowing users to have fluid bidirectional voice conversations with their reading assistant, explore and summarize currently open books or documents, and command reading playback hands-free from the floating bubble overlay.

## User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Choices from Clarification Phase:**
> - **Dual Capability:** The assistant operates both as an **interactive document companion** (explaining concepts, summarizing chapters, answering contextual questions about the active book) and as a **voice-controlled navigator** (executing spoken commands like "pause reading", "resume", "read faster", "skip to next page").
> - **Access Points & Governance:** Invoked seamlessly from the **Floating Bubble overlay** while reading or multitasking across apps, with dedicated feature toggles, preferences, and status managed in the **Account Profile screen** (`AccountScreen`).
> - **Interaction Style:** **Continuous bidirectional live audio streaming** paired with a **real-time synchronized text transcript** HUD showing conversational turns.

---

## 1. Overview & Core Concept

- **What It Does**: ReadMe AI transforms passive text-to-speech into an active, collaborative reading partner. When activated from the floating bubble, users can speak naturally to ask questions about the book they are reading, request chapter summaries, clarify difficult passages, or give voice commands to adjust playback speed, pause, resume, or change pages.
- **Target Audience / Persona**: Commuters, students, audiobook enthusiasts, and readers with visual or mobility impairments who need hands-free interaction, comprehension support, and fluid spoken control over their reading library.
- **Key Value**: Delivers zero-latency, natural voice interaction directly alongside local document parsing (PDF, EPUB, TXT) without interrupting background reading or requiring constant screen touches.

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Activation via Floating Bubble**:
   - The user taps the floating bubble overlay or expands the bubble menu.
   - An "AI Voice Conversation" pill with an animated audio wave icon appears.
   - Tapping it expands the floating pill into a sleek, compact semi-transparent voice HUD showing a glowing live waveform visualizer.

2. **Active Voice Conversation**:
   - The user speaks naturally: *"Can you summarize the main argument in chapter 3?"* or *"Pause reading and explain what the author meant by this metaphor."*
   - As the user speaks, the live audio wave pulses in cyan/teal.
   - Gemini Live streams audio responses with sub-second latency while a synchronized live transcript streams text token-by-token directly in the HUD.

3. **Hands-Free Reader Navigation**:
   - The user commands: *"Read faster"* or *"Skip to the next page"*.
   - ReadMe AI parses the intent, triggers the local `ReadingSessionCoordinator` / `ReadMeSpeechEngine`, confirms the action with a brief audio acknowledgment (*"Adjusting speed to 1.5x"*), and resumes reading.

4. **Account Profile & AI Management**:
   - In the Home screen top bar, tapping the Account icon opens `AccountScreen`.
   - The previous "ReadMe AI (Coming Soon)" placeholder card is transformed into an active **ReadMe AI Management Card**, allowing the user to configure voice personality, model selection, microphone sensitivity, and document context sharing.

### Visual Identity & Theme

- **Aesthetic Direction**: Premium luxury dark mode matching ReadMe's signature dark aesthetic (`DarkBackground: #121212`, `DarkSurface: #1E1E1E`).
- **Color Accents**:
  - Primary Accent: Crisp Teal (`#00E5FF` / `#00B4D8`) representing active speech playback and document reading.
  - AI Live State: Radiant Violet/Indigo glow (`#7C4DFF` / `#651FFF`) signifying active Gemini Live connection and listening states.
- **Typography**: Clean, high-legibility Material typography with prominent headline titles and readable, accessibility-compliant body transcripts.
- **Touch & Accessibility**: All interactive triggers and overlay buttons maintain a minimum 48dp touch target with full TalkBack content descriptions.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Dual Companion + Playback Command Engine**
  - *Chosen Approach*: Equip the Live session system prompt with structured tool-calling / function execution capabilities (`pauseReading`, `resumeReading`, `setPlaybackSpeed`, `nextPage`, `previousPage`) while grounding its conversational context with the currently active document's text and metadata.
  - *Why*: Allows seamless transition between casual reading discussion and direct playback control without forcing the user to switch modes or use rigid syntax.
  - *Alternatives Considered*: Pure Q&A without playback commands would force the user to still tap buttons to pause or seek; rigid keyword commands alone would lose conversational intelligence.

- **Decision 2: Floating Bubble HUD + Account Configuration Hub**
  - *Chosen Approach*: Anchor live conversation trigger in the floating bubble overlay for system-wide access over any reader app, while centralizing configuration, permissions, and status in `AccountScreen`.
  - *Why*: The floating bubble is already ReadMe's signature cross-app interface; placing AI voice conversation there allows users to talk about books even when reading inside Kindle, Moon+ Reader, or local PDF viewers.
  - *Alternatives Considered*: A full-screen dialog would obscure the document being read; a bottom drawer inside ReadMe would not be usable when reading across other applications.

- **Decision 3: Bidirectional Audio Stream with Real-Time Transcript**
  - *Chosen Approach*: Stream microphone PCM audio in real-time to the Live API session while receiving streaming audio output, accompanied by real-time streaming text events rendered in a scrollable transcript box.
  - *Why*: Accommodates both auditory listeners and visual scanners, ensuring users can verify voice commands or review complex explanations.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ReadMe Application                              │
│                                                                        │
│   ┌────────────────────────┐              ┌────────────────────────┐   │
│   │    ReadMeScreen.kt     │              │    AccountScreen.kt    │   │
│   │  (Reader UI & Nav)     │              │ (AI Config & Toggles)  │   │
│   └───────────┬────────────┘              └───────────┬────────────┘   │
│               │                                       │                │
│               ▼                                       ▼                │
│   ┌────────────────────────────────────────────────────────────┐       │
│   │                    ReadMeViewModel.kt                      │       │
│   └──────┬────────────────────┬────────────────────────┬───────┘       │
│          │                    │                        │               │
│          ▼                    ▼                        ▼               │
│   ┌──────────────┐    ┌──────────────┐    ┌────────────────────────┐   │
│   │ReadingSession│    │  ReadMeTTS   │    │    ReadMeAiManager     │   │
│   │ Coordinator  │    │    Engine    │    │ (Gemini Live Session)  │   │
│   └──────────────┘    └──────────────┘    └───────────┬────────────┘   │
│                                                       │                │
│   ┌───────────────────────────────────────────────────┼────────────┐   │
│   │ Floating Bubble Overlay Service                   │            │   │
│   │                                                   │            │   │
│   │   ┌───────────────────────────┐                   │            │   │
│   │   │  ReadMeFloatingBubble.kt  │◄──────────────────┘            │   │
│   │   │   • Live Waveform View    │                                │   │
│   │   │   • Mic Audio Input       │                                │   │
│   │   │   • Real-Time Transcript  │                                │   │
│   │   └───────────────────────────┘                                │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     Gemini Live API Endpoint                           │
│     models/gemini-3.8-flash (Real-Time Bidirectional WebSocket)        │
│          • Input: 16kHz PCM audio stream + active doc context          │
│          • Output: Real-time audio stream + streaming tokens           │
│          • Tools: pauseReading, resumeReading, seekPosition, etc.      │
└────────────────────────────────────────────────────────────────────────┘
```

### Core Components & State Management

1. **`ReadMeAiManager.kt`**:
   - Manages connection lifecycle with Gemini Live API via WebSockets / Firebase AI Logic.
   - Pipes microphone input (`AudioRecord`, 16kHz 16-bit mono PCM) and plays received audio buffers through `AudioTrack`.
   - Injects active document context (`ReadingDocumentMetadata`, current chapter title, current segment text).
   - Exposes StateFlows: `isListening`, `isSpeaking`, `liveTranscript`, `connectionState`.

2. **Tool / Function Calling Handlers**:
   - `handleReaderCommand(name: String, args: Map<String, Any>)`:
     - `pause()` -> calls `readingSessionCoordinator.pauseReading()`
     - `resume()` -> calls `readingSessionCoordinator.resumeReading()`
     - `setSpeed(speed: Float)` -> updates speech settings in `ReadMeSettingsRepository`
     - `nextPage()` / `previousPage()` -> triggers page navigation

3. **Floating Bubble HUD (`ReadMeFloatingBubble.kt`)**:
   - Compact expanding pill: shows mic mute toggle, active audio visualizer wave, and an expandable 2-line transcript preview.

4. **Account Screen Integration (`AccountScreen.kt`)**:
   - Replaces the static coming-soon card with active configuration: AI voice selection, auto-listen mode toggle, and connection diagnostic status.
