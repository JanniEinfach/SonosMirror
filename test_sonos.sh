#!/bin/bash
# Schnell-Test: Sagt der Sonos er soll einen WAV-Test-Stream abspielen
# Benutze: bash test_sonos.sh <stream-url>
# Beispiel: bash test_sonos.sh http://192.168.178.45:8888/stream.wav

SONOS_IP="192.168.178.76"
STREAM_URL="${1:-http://192.168.178.45:8888/stream.wav}"

echo "▶ SetAVTransportURI → $STREAM_URL"
curl -s -X POST "http://$SONOS_IP:1400/MediaRenderer/AVTransport/Control" \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"' \
  -d "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID><CurrentURI>$STREAM_URL</CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>"

sleep 0.5

echo ""
echo "▶ Play"
curl -s -X POST "http://$SONOS_IP:1400/MediaRenderer/AVTransport/Control" \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  -H 'SOAPAction: "urn:schemas-upnp-org:service:AVTransport:1#Play"' \
  -d '<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>'

echo ""
echo "✓ Fertig. Sonos sollte jetzt $STREAM_URL abspielen."
