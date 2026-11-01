# Booking write simulator for the ramp load test (loadtest/simulator/simulate.py).
# Pinned: one image = one set of numbers.
FROM python:3.13.1-slim

RUN pip install --no-cache-dir "psycopg[binary]==3.2.3" tzdata==2025.2

WORKDIR /loadtest/simulator
COPY simulate.py .

ENTRYPOINT ["python", "-u", "simulate.py"]
