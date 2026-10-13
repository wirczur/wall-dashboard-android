# Wall Dashboard – aplikacja na panele (Android 10+)

Zastępuje Fully Kiosk na słabych panelach (np. Grenton S6PE). Pokazuje dashboard z add-onu Wall Dashboard
i dokłada natywnie: dźwięki / mowę / głośność (zgodne z Fully – add-on działa bez zmian), wygaszanie ekranu,
polecenia z Home Assistant i tryb kiosku. **Etap 1** – interkom i kamery natywnie przyjdą w kolejnych wersjach.

Wymaga add-onu **Wall Dashboard 0.22.0** lub nowszego.

## 1. Zbudowanie APK (Android Studio)

1. Android Studio (Koala lub nowsze) → **Open** → wskaż ten katalog (`WallDashboardApp`).
2. Poczekaj na „Gradle sync” (pierwszy raz pobiera Gradle 8.9 i Android SDK 34 – kilka minut).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. Plik: `app/build/outputs/apk/debug/app-debug.apk` (lub `release/app-release.apk` po `./gradlew assembleRelease`;
   wersja release jest podpisana kluczem debug, więc instaluje się bez konfiguracji keystore).

Bez Android Studio: wrzuć katalog do prywatnego repozytorium GitHub – workflow `.github/workflows/build.yml`
zbuduje APK (Actions → Build APK → Artifacts).

## 2. Instalacja na panelu

- Skopiuj APK i zainstaluj (tak jak instalowałeś Fully), albo z komputera: `adb install -r app-debug.apk`.
- Uruchom **Wall Dashboard**, wpisz adres add-onu (np. `192.168.5.4:8100` – aplikacja sama sprawdzi HTTPS i HTTP).
- Panel pokaże **6-cyfrowy kod** → w Home Assistant: Wall Dashboard → **Tablety** → „Aplikacja Wall Dashboard (Android) – parowanie”
  → wpisz kod, wybierz istniejący tablet (np. ten, którego używał Fully) albo „Nowy tablet” → **Sparuj**.
- Zgoda na mikrofon: przy pierwszym uruchomieniu (potrzebna do obecnego interkomu w przeglądarce).
- Ustaw jako **ekran główny**: naciśnij Home → wybierz „Wall Dashboard” → „Zawsze”. Od tej chwili startuje razem z panelem.

## 3. Tryb kiosku (opcjonalnie, zalecane)

Bez paska systemowego, bez ekranu blokady i z prawdziwym wyłączaniem ekranu – raz z komputera:

```
adb shell dpm set-device-owner pl.walldashboard.app/.AdminReceiver
```

Warunki Androida: na panelu nie może być kont (Google itp.) ani innego właściciela urządzenia.
Jeśli Fully był ustawiony jako właściciel urządzenia, najpierw zdejmij go w Fully (Settings → Device Management)
albo `adb shell dpm remove-active-admin de.ozerov.fully/.DeviceOwnerReceiver`.
Wycofanie: w ustawieniach aplikacji „Wybierz ekran główny”, a z komputera
`adb shell dpm remove-active-admin pl.walldashboard.app/.AdminReceiver`.

## 4. Ukryte ustawienia na panelu

Przytrzymaj **lewy górny róg ekranu przez 3 s**. PIN ustawiasz w add-onie (Tablety → Ekran i aplikacja).
Tam: przeładowanie, restart, Wi-Fi, ustawienia Androida, wybór ekranu głównego (wyjście z kiosku), uruchomienie Fully,
ponowne parowanie.

## 5. Wygaszanie i polecenia z HA

Add-on → Tablety → **Ekran i aplikacja**: czarny ekran / przyciemnienie / wyłączenie, po ilu minutach bez dotyku,
okna godzinowe (np. 23:00–6:30), jasność, PIN. Pop-up, dzwonek i interkom budzą ekran.

Z Home Assistant (automatyzacja / skrypt):

```yaml
action: event.fire   # w automatyzacji: event: wall_dashboard_command
data:
  event_type: wall_dashboard_command
  event_data:
    tablet: "Grenton piętro"   # nazwa, id albo all
    command: saver             # reload | restart | screen-on | screen-off | saver | brightness
    value: 40                  # tylko dla brightness (0–100)
```

Te same polecenia działają z Fully (przez jego interfejs JavaScript).

## Diagnostyka

- `chrome://inspect` w Chrome na PC (panel przez ADB) – konsola i podgląd strony dashboardu.
- `adb logcat -s WallDashboard` – logi aplikacji.
