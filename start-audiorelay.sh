#!/bin/bash
# Startet audiorelay auf der Formuler Z12 und richtet Sonos ein.
# Einmal ausführen nach jedem Box-Neustart.

BOX_IP="192.168.178.45"
SONOS_IP="192.168.178.76"
RELAY_PORT=9877
ADB="adb -s ${BOX_IP}:5555"

echo "SonosMirror Setup..."
echo ""

# ADB verbinden
$ADB get-state &>/dev/null || adb connect "${BOX_IP}:5555"

# AppOp sicherstellen
$ADB shell "cmd appops set com.android.shell RECORD_AUDIO_OUTPUT allow" 2>/dev/null

# Alten audiorelay ggf. beenden
OLD_PID=$($ADB shell "ps -A | grep app_process" | awk '{print $2}')
if [ -n "$OLD_PID" ]; then
    echo "Stoppe alten audiorelay (PID $OLD_PID)..."
    $ADB shell "kill $OLD_PID" 2>/dev/null
    sleep 1
fi

# audiorelay starten
echo "Starte audiorelay auf Port ${RELAY_PORT}..."
$ADB shell "CLASSPATH=/data/local/tmp/audiorelay.dex app_process / com.janni.audiorelay.Main > /data/local/tmp/ar.log 2>&1 &"
sleep 3

# Status prüfen
$ADB shell "tail -3 /data/local/tmp/ar.log"
echo ""
echo "Stream-URL: http://${BOX_IP}:${RELAY_PORT}/stream.wav"
echo ""
echo "Jetzt in SonosMirror-App: Relay-Port = ${RELAY_PORT}, dann 'Stream starten' tippen."
echo ""
echo "Oder per ADB direkt testen:"
echo "  curl -o /dev/null -s -w 'Bytes: %{size_download}\\n' --max-time 3 http://${BOX_IP}:${RELAY_PORT}/stream.wav"
