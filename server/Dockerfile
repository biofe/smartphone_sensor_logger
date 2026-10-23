FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 VELOWIND_DATA_DIR=/data
RUN useradd --system --uid 10001 --no-create-home velowind
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY velowind_upload ./velowind_upload

USER velowind
EXPOSE 8080
CMD ["uvicorn", "velowind_upload.main:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log", "--timeout-keep-alive", "30"]
