# Human accessibility acceptance — owner-confirmed; detailed test scope unreported

Use only the synthetic fixture below; it creates a fresh database, disables collection, binds an ephemeral loopback port and stops after ten minutes. It prints a private credential-file path; do not publish that file. The fixture launcher has passed a bounded startup/shutdown self-test. The owner confirmed acceptance; no reviewer environment or per-flow PASS result is invented.

```sh
python3 tools/start-accessibility-fixture.py --jar /PATH/torchnode-1.0.0.jar --expected-sha256 89f9391bad5ce3d8a5b8230a11dc478708049786efa5c8f37bf19123f675e02e --jdk /PATH/TO/JDK21 --seconds 600
```

1. **Desktop/mobile:** log in, open dashboard, topology, discovery, analytics, reports and node detail at desktop width and 390px. Check clipping, text scaling/200–400% zoom, contrast and empty/error states. Record mobile emulation separately from physical-device review.
2. **Keyboard:** use Tab/Shift-Tab/Enter/Space/Escape through login, navigation, filters and dialogs. Check visible/ordered focus, restored focus and no trap; test any offered skip link.
3. **Screen reader:** name the actual reader/version (e.g. VoiceOver/Safari or NVDA/Firefox); check login labels/errors, landmarks/headings, filter labels, status announcements and topology descriptions in reading order. Missing evidence must read as unknown; reported neighbors must remain distinct from verified sessions.
4. **Record:** reviewer, UTC time, OS/browser/reader versions, exact artifact hash, pages/viewports, PASS/FAIL per item and defects. Explicitly state acceptance only after performing the review; fix blocking defects and review the affected flow again.

The exact final image JAR passes native Chrome login, desktop/mobile overflow, focus/AX labels, sampled contrast and axe4.10.3 with zero violations. Incomplete topology checks still require human review; automated PASS does not establish human acceptance. Remote CI remains PENDING. Human acceptance is owner-confirmed with detailed scope unreported; no per-flow human PASS is claimed. This procedure is not WCAG certification.

The new legal-notice surfaces were validated automatically in native Chrome at desktop/mobile sizes with axe. These are agent-run automated results, not additional human review or WCAG certification.
