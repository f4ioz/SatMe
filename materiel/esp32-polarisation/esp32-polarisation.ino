/*
 * SatMe — amateur radio satellite tracking
 * Copyright (C) 2025-2026  Olivier Gouyen (F4IOZ)
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Free software under the GNU GPL, version 2 or later. Without any warranty.
 * The full licence text is in the LICENSE file.
 *
 * Antenna polarisation box: an ESP32 DevKit driving the relay trees of
 * WiMo remote polarisation switches (18080 to 18083) through a TBD62083A,
 * ordered by SatMe-PC over USB serial (115200 baud, one line a command).
 *
 *   POL U H|V|D|G   UHF antenna: horizontal, vertical, right / left circular
 *   POL V H|V|D|G   VHF antenna (second tree, or one H/V relay on KA')
 *   ETAT?           -> ETAT SEL=AUTO|V|H|D|G|? U=x V=x TX=0|1 FW=...
 *   ID?             -> ID SATME-POL ESP32 FW=...
 * Answers: OK POL U H, ERR MANUEL (selector off AUTO), ERR TX (SEND
 * active), ERR SYNTAXE. An ETAT line is also sent by itself whenever the
 * selector, SEND or a polarisation changes.
 *
 * Rules held here (and by the wiring):
 *  - at start-up every relay at rest: no voltage, the antenna vertical;
 *  - selector off AUTO: every output released, every command refused —
 *    the selector feeds the WiMo box itself and has the last word;
 *  - SEND active (the rig's line grounded while transmitting): no switching;
 *  - a change goes through "no voltage": KA released first, KB/KC set
 *    behind it, KA energised last. The tree (KA -> KB -> KC) can never
 *    feed two pins, whatever this program does.
 *
 * U= in ETAT is the UHF antenna's real polarisation: the selector's own
 * position off AUTO, what the relays give in AUTO. V= is what the VHF
 * relays give (released: vertical).
 */

#define FW_VERSION "1.0"

// Outputs to the TBD62083A (IN1..IN6): KA KB KC (UHF), KA' KB' KC' (VHF).
static const uint8_t PINS_RELAYS[2][3] = { { 23, 22, 21 }, { 19, 18, 17 } };
// Selector, circuit B (to ground in its position): V, H, D, G, AUTO.
static const uint8_t PINS_SELECTOR[5] = { 32, 33, 25, 26, 27 };
static const char SELECTOR_CODES[5] = { 'V', 'H', 'D', 'G', 'A' };
// The rig's SEND line through 1 kOhm: grounded while transmitting.
static const uint8_t PIN_SEND = 13;

static const unsigned long SAMPLE_MS = 5;        // inputs read every 5 ms
static const unsigned long SELECTOR_STABLE_MS = 50;
static const unsigned long SEND_STABLE_MS = 20;
static const unsigned long RELAY_MS = 25;        // a small signal relay drops or pulls in within ~10 ms

static bool relays[2][3];                        // [band][KA,KB,KC], true: energised
static char selector = '?';                      // 'A' for AUTO, '?' between positions
static bool sendActive = false;

static char selectorCandidate = '?';
static unsigned long selectorSinceMs = 0;
static bool sendCandidate = false;
static unsigned long sendSinceMs = 0;
static unsigned long lastSampleMs = 0;

static char line[48];
static uint8_t lineLength = 0;
static bool lineOverflow = false;

// ------------------------------------------------------------------ relays

static void setRelay(uint8_t band, uint8_t k, bool on) {
  relays[band][k] = on;
  digitalWrite(PINS_RELAYS[band][k], on ? HIGH : LOW);
}

// The polarisation relays [k] give: KA at rest -> none (V); KB at rest -> pin 5 (H); KC at rest -> pin 1 (D); else pin 2 (G).
static char polarisationOf(const bool k[3]) {
  if (!k[0]) return 'V';
  if (!k[1]) return 'H';
  if (!k[2]) return 'D';
  return 'G';
}

// The relays for [p]; those "without effect" left at rest.
static void targetOf(char p, bool t[3]) {
  t[0] = p != 'V';
  t[1] = p == 'D' || p == 'G';
  t[2] = p == 'G';
}

// [p] on [band], through "no voltage": KA released first, KB/KC behind it, KA last.
static void applyPolarisation(uint8_t band, char p) {
  bool t[3];
  targetOf(p, t);
  if (relays[band][0] == t[0] && relays[band][1] == t[1] && relays[band][2] == t[2]) return;
  if (relays[band][0]) { setRelay(band, 0, false); delay(RELAY_MS); }
  if (relays[band][1] != t[1] || relays[band][2] != t[2]) {
    setRelay(band, 1, t[1]);
    setRelay(band, 2, t[2]);
    delay(RELAY_MS);
  }
  if (t[0]) { setRelay(band, 0, true); delay(RELAY_MS); }
}

static void releaseAll() {
  for (uint8_t b = 0; b < 2; b++) applyPolarisation(b, 'V');
}

// ------------------------------------------------------------------ state

static char realPolarisation(uint8_t band) {
  // Off AUTO the selector drives the UHF box itself: its position is the antenna's.
  if (band == 0 && selector != 'A' && selector != '?') return selector;
  return polarisationOf(relays[band]);
}

static void printState() {
  Serial.print("ETAT SEL=");
  if (selector == 'A') Serial.print("AUTO"); else Serial.print(selector);
  Serial.print(" U="); Serial.print(realPolarisation(0));
  Serial.print(" V="); Serial.print(realPolarisation(1));
  Serial.print(" TX="); Serial.print(sendActive ? '1' : '0');
  Serial.print(" FW="); Serial.println(FW_VERSION);
}

// ------------------------------------------------------------------ inputs

// The selector's position now: the one input grounded; '?' for none or several (between two positions).
static char readSelector() {
  char found = '?';
  uint8_t n = 0;
  for (uint8_t i = 0; i < 5; i++) {
    if (digitalRead(PINS_SELECTOR[i]) == LOW) { found = SELECTOR_CODES[i]; n++; }
  }
  return n == 1 ? found : '?';
}

static void sampleInputs(unsigned long now) {
  char s = readSelector();
  if (s != selectorCandidate) { selectorCandidate = s; selectorSinceMs = now; }
  if (selectorCandidate != selector && now - selectorSinceMs >= SELECTOR_STABLE_MS) {
    selector = selectorCandidate;
    // Off AUTO (or between two positions): nothing driven by the ESP32 any more.
    if (selector != 'A') releaseAll();
    printState();
  }
  bool tx = digitalRead(PIN_SEND) == LOW;
  if (tx != sendCandidate) { sendCandidate = tx; sendSinceMs = now; }
  if (sendCandidate != sendActive && now - sendSinceMs >= SEND_STABLE_MS) {
    sendActive = sendCandidate;
    printState();
  }
}

// ------------------------------------------------------------------ commands

static char upper(char c) { return (c >= 'a' && c <= 'z') ? (char)(c - 'a' + 'A') : c; }

static void handleLine(char *l) {
  // Upper case, spaces folded, trimmed.
  char w[48];
  uint8_t n = 0;
  bool space = true;
  for (uint8_t i = 0; l[i] && n < sizeof(w) - 1; i++) {
    char c = upper(l[i]);
    if (c == ' ' || c == '\t') { if (!space) { w[n++] = ' '; space = true; } continue; }
    w[n++] = c; space = false;
  }
  while (n > 0 && w[n - 1] == ' ') n--;
  w[n] = 0;
  if (n == 0) return;

  if (strcmp(w, "ETAT?") == 0) { printState(); return; }
  if (strcmp(w, "ID?") == 0) { Serial.println("ID SATME-POL ESP32 FW=" FW_VERSION); return; }
  // "POL U H": exactly seven characters.
  if (n == 7 && strncmp(w, "POL ", 4) == 0 && w[5] == ' ' && (w[4] == 'U' || w[4] == 'V') &&
      (w[6] == 'V' || w[6] == 'H' || w[6] == 'D' || w[6] == 'G')) {
    if (selector != 'A') {
      Serial.print("ERR MANUEL selecteur sur ");
      Serial.println(selector);
      return;
    }
    if (sendActive) { Serial.println("ERR TX emission en cours"); return; }
    uint8_t band = w[4] == 'U' ? 0 : 1;
    char before = polarisationOf(relays[band]);
    applyPolarisation(band, w[6]);
    Serial.print("OK POL "); Serial.print(w[4]); Serial.print(' '); Serial.println(w[6]);
    if (before != w[6]) printState();
    return;
  }
  Serial.print("ERR SYNTAXE ");
  Serial.println(w);
}

static void readSerial() {
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      line[lineLength] = 0;
      if (lineOverflow) Serial.println("ERR SYNTAXE ligne trop longue");
      else handleLine(line);
      lineLength = 0; lineOverflow = false;
    } else if (lineLength < sizeof(line) - 1) {
      line[lineLength++] = c;
    } else {
      lineOverflow = true;
    }
  }
}

// ------------------------------------------------------------------ Arduino

void setup() {
  // Every relay at rest before anything else: no voltage, the antennas vertical.
  for (uint8_t b = 0; b < 2; b++) {
    for (uint8_t k = 0; k < 3; k++) {
      digitalWrite(PINS_RELAYS[b][k], LOW);
      pinMode(PINS_RELAYS[b][k], OUTPUT);
      digitalWrite(PINS_RELAYS[b][k], LOW);
      relays[b][k] = false;
    }
  }
  for (uint8_t i = 0; i < 5; i++) pinMode(PINS_SELECTOR[i], INPUT_PULLUP);
  pinMode(PIN_SEND, INPUT_PULLUP);
  Serial.begin(115200);
  delay(SELECTOR_STABLE_MS);
  // The inputs as they are now, without waiting for the debounce.
  selector = selectorCandidate = readSelector();
  sendActive = sendCandidate = digitalRead(PIN_SEND) == LOW;
  selectorSinceMs = sendSinceMs = lastSampleMs = millis();
  Serial.println("ID SATME-POL ESP32 FW=" FW_VERSION);
  printState();
}

void loop() {
  unsigned long now = millis();
  if (now - lastSampleMs >= SAMPLE_MS) { lastSampleMs = now; sampleInputs(now); }
  readSerial();
}
