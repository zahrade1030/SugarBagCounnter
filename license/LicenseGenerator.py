import base64
import hashlib
import json
import os
import secrets
import sys
from datetime import datetime, timedelta, timezone

PRODUCT_ID = "VEA_SUGAR_COUNTER"
LICENSE_VERSION = 1
LICENSE_DURATION_MONTHS = 36


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode("utf-8").rstrip("=")


def get_device_id():
    return input("Device ID مشتری: ").strip().upper()


def get_customer_name():
    return input("نام مشتری: ").strip()


def get_private_key_path():
    return input("مسیر فایل کلید خصوصی RSA: ").strip()


def load_private_key(path):
    try:
        from cryptography.hazmat.primitives import serialization
        with open(path, "rb") as f:
            return serialization.load_pem_private_key(
                f.read(),
                password=None
            )
    except ImportError:
        print("کتابخانه cryptography نصب نیست.")
        print("اجرا کنید: pip install cryptography")
        sys.exit(1)
    except Exception as e:
        print("خطا در خواندن کلید خصوصی:", e)
        sys.exit(1)


def add_months(date, months):
    month = date.month - 1 + months
    year = date.year + month // 12
    month = month % 12 + 1

    import calendar
    day = min(date.day, calendar.monthrange(year, month)[1])

    return date.replace(year=year, month=month, day=day)


def generate_license(device_id, customer_name, private_key):
    now = datetime.now(timezone.utc)
    expiry = add_months(now, LICENSE_DURATION_MONTHS)

    payload = {
        "licenseId": secrets.token_hex(16).upper(),
        "customerName": customer_name,
        "deviceId": device_id,
        "issuedAt": int(now.timestamp() * 1000),
        "expiresAt": int(expiry.timestamp() * 1000),
        "product": PRODUCT_ID,
        "version": LICENSE_VERSION
    }

    payload_bytes = json.dumps(
        payload,
        separators=(",", ":"),
        ensure_ascii=False
    ).encode("utf-8")

    encoded_payload = b64url_encode(payload_bytes)

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    signature = private_key.sign(
        encoded_payload.encode("utf-8"),
        padding.PKCS1v15(),
        hashes.SHA256()
    )

    encoded_signature = b64url_encode(signature)

    license_code = f"VEA1.{encoded_payload}.{encoded_signature}"

    return license_code, payload


def main():
    print("=" * 50)
    print(" VEA SUGAR COUNTER - LICENSE GENERATOR")
    print("=" * 50)

    device_id = get_device_id()
    customer_name = get_customer_name()
    private_key_path = get_private_key_path()

    if not device_id or not customer_name or not private_key_path:
        print("اطلاعات ناقص است.")
        return

    private_key = load_private_key(private_key_path)

    license_code, payload = generate_license(
        device_id,
        customer_name,
        private_key
    )

    print("\n" + "=" * 50)
    print("LICENSE GENERATED SUCCESSFULLY")
    print("=" * 50)

    print("\nCustomer:", payload["customerName"])
    print("License ID:", payload["licenseId"])
    print("Device ID:", payload["deviceId"])

    issued = datetime.fromtimestamp(
        payload["issuedAt"] / 1000,
        timezone.utc
    )

    expires = datetime.fromtimestamp(
        payload["expiresAt"] / 1000,
        timezone.utc
    )

    print("Issued:", issued.strftime("%Y-%m-%d"))
    print("Expires:", expires.strftime("%Y-%m-%d"))
    print("Duration:", LICENSE_DURATION_MONTHS, "months")

    print("\nACTIVATION CODE:")
    print("-" * 50)
    print(license_code)
    print("-" * 50)

    os.makedirs("licenses", exist_ok=True)

    filename = f"licenses/{payload['licenseId']}.txt"

    with open(filename, "w", encoding="utf-8") as f:
        f.write("VEA SUGAR COUNTER LICENSE\n")
        f.write("=" * 40 + "\n")
        f.write(f"Customer: {customer_name}\n")
        f.write(f"Device ID: {device_id}\n")
        f.write(f"License ID: {payload['licenseId']}\n")
        f.write(f"Expires: {expires.strftime('%Y-%m-%d')}\n\n")
        f.write("ACTIVATION CODE:\n")
        f.write(license_code)

    print("\nSaved to:", filename)


if __name__ == "__main__":
    main()
