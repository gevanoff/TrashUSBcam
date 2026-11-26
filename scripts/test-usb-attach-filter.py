"""Check the source manifest and USB attach rules without an Android SDK."""

from pathlib import Path
import unittest
from xml.etree import ElementTree as ET


MAIN = Path(__file__).resolve().parents[1] / "app" / "src" / "main"
ANDROID = "{http://schemas.android.com/apk/res/android}"
USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"


class UsbAttachFilterTest(unittest.TestCase):
    def test_only_usb_video_is_registered_for_auto_open(self):
        manifest = ET.parse(MAIN / "AndroidManifest.xml")
        handlers = [
            activity for activity in manifest.findall("./application/activity")
            + manifest.findall("./application/activity-alias")
            if any(
                action.get(ANDROID + "name") == USB_ATTACHED
                for action in activity.findall("./intent-filter/action")
            )
        ]
        self.assertEqual([h.get(ANDROID + "name") for h in handlers], [".MainActivity"])
        metadata = [
            item.get(ANDROID + "resource")
            for item in handlers[0].findall("meta-data")
            if item.get(ANDROID + "name") == USB_ATTACHED
        ]
        self.assertEqual(metadata, ["@xml/device_filter"])

        # No wildcard, vendor allowlist or subclass restriction. Android checks
        # this class against both the device descriptor and each interface.
        rules = ET.parse(MAIN / "res/xml/device_filter.xml").findall(".//usb-device")
        self.assertEqual([rule.attrib for rule in rules], [{"class": "14"}])


if __name__ == "__main__":
    unittest.main()
