# YOLO Aimbot Project Instructions

## General Rules
- **DO NOT RUN BUILDS:** The agent is strictly prohibited from running `./gradlew`, `assembleDebug`, or any build-related commands. The user will handle all compilation and building.
- **Focus on Logic:** Prioritize implementing and fixing the core AI logic, coordinate mapping, and IPC mechanisms.

## Architecture
- **Engine:** `GameAiEngine` is the master orchestrator. It listens for frames from `ScreenCaptureService`.
- **Inference:** Uses ONNX Runtime. YOLO output is usually transposed (`[84, 8400]`).
- **Touch Injection:** Uses `uinput` for low-latency injection. C++ daemon is used via Shizuku.

## Coding Standards
- Use `gameai` package for all new AI-related components.
- Maintain separation between UI (Compose), Service (MediaProjection), and Engine (Inference/PID).
