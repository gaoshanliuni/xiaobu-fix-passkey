"""Synthetic, non-authenticating QR fixtures. No real credential data."""
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent / "python"))
import qrcode

output = Path(__file__).resolve().parent.parent / "analysis" / "fixtures"
output.mkdir(parents=True, exist_ok=True)
fixtures = {
    "fido-synthetic": "FIDO:/" + "0123456789" * 20,
    "ordinary-url": "https://example.com/",
    "ordinary-text": "Xiaobu ordinary QR regression test 2026-10-15",
}
for name, raw in fixtures.items():
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=16, border=6)
    qr.add_data(raw, optimize=0)
    qr.make(fit=True)
    qr.make_image(fill_color="black", back_color="white").save(output / f"{name}.png")
    (output / f"{name}.txt").write_text(raw, encoding="utf-8")
    print(name)
