import unittest
import uuid

from fastapi import HTTPException

import app


class BackendUtilityTests(unittest.TestCase):
    def test_password_hash_verifies_and_uses_salt(self):
        first = app.hash_password("CorrectHorseBatteryStaple!")
        second = app.hash_password("CorrectHorseBatteryStaple!")
        self.assertNotEqual(first, second)
        self.assertTrue(app.verify_password("CorrectHorseBatteryStaple!", first))
        self.assertFalse(app.verify_password("incorrect", first))

    def test_token_digest_is_stable_and_not_plaintext(self):
        token = "session-token-value"
        digest = app.token_digest(token)
        self.assertEqual(digest, app.token_digest(token))
        self.assertNotEqual(digest, token)
        self.assertEqual(len(digest), 64)

    def test_valid_user_accepts_uuid(self):
        value = str(uuid.uuid4())
        self.assertEqual(app.valid_user(value), value)

    def test_valid_user_rejects_non_uuid(self):
        with self.assertRaises(HTTPException) as raised:
            app.valid_user("not-a-uuid")
        self.assertEqual(raised.exception.status_code, 400)


if __name__ == "__main__":
    unittest.main()
