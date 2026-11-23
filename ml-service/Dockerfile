# PulseOps ML service — small, non-root, no build tools in the final image.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --system --uid 10001 pulseops
COPY app ./app
USER pulseops
EXPOSE 8500
# Inside a container network the service must listen on all interfaces; publish the port
# only to the backend's network, never to the internet.
CMD ["uvicorn", "--factory", "app.main:create_app", "--host", "0.0.0.0", "--port", "8500", "--workers", "1"]
