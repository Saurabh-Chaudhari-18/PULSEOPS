"""BodyLimitMiddleware at the ASGI level (no web framework needed)."""

import asyncio

from app.limits import BodyLimitMiddleware


async def echo_length(scope, receive, send):
    body = b""
    while True:
        message = await receive()
        body += message.get("body", b"")
        if not message.get("more_body"):
            break
    await send({"type": "http.response.start", "status": 200, "headers": []})
    await send({"type": "http.response.body", "body": str(len(body)).encode()})


def call(chunks, headers=()):
    messages = [{"type": "http.request", "body": c, "more_body": i < len(chunks) - 1} for i, c in enumerate(chunks)]
    sent = []

    async def receive():
        return messages.pop(0) if messages else {"type": "http.disconnect"}

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "method": "POST", "headers": list(headers)}
    asyncio.run(BodyLimitMiddleware(echo_length, 100)(scope, receive, send))
    return sent[0]["status"], sent[1]["body"]


def test_small_bodies_are_replayed_intact():
    assert call([b"a" * 60, b"b" * 40]) == (200, b"100")


def test_chunked_body_over_the_limit_is_refused_without_reading_the_rest():
    status, body = call([b"a" * 60, b"b" * 60, b"c" * 1000])
    assert status == 413
    assert b"too large" in body


def test_declared_length_over_the_limit_is_refused_up_front():
    assert call([b"x"], headers=[(b"content-length", b"5000")])[0] == 413
    assert call([b"x"], headers=[(b"content-length", b"abc")])[0] == 413
