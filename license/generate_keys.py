import os
import base64

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization


def generate_keys():
    private_key = rsa.generate_private_key(
        public_exponent=65537,
        key_size=2048
    )

    public_key = private_key.public_key()

    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption()
    )

    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )

    os.makedirs("keys", exist_ok=True)

    with open("keys/private_key.pem", "wb") as f:
        f.write(private_pem)

    with open("keys/public_key.pem", "wb") as f:
        f.write(public_pem)

    public_der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo
    )

    public_base64 = base64.b64encode(public_der).decode("utf-8")

    print("=" * 50)
    print("KEY GENERATION COMPLETE")
    print("=" * 50)
    print("\nPUBLIC KEY BASE64:")
    print(public_base64)
    print("\nPrivate key saved locally.")
    print("DO NOT SHARE OR UPLOAD THE PRIVATE KEY.")


if __name__ == "__main__":
    generate_keys()
