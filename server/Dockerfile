FROM python:3.12-slim
ARG GET_IPLAYER_REF=master
RUN apt-get update && apt-get install -y --no-install-recommends \
      perl ffmpeg atomicparsley curl ca-certificates libwww-perl liblwp-protocol-https-perl \
      libmojolicious-perl libxml-libxml-perl libcgi-pm-perl libio-socket-ssl-perl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://raw.githubusercontent.com/get-iplayer/get_iplayer/${GET_IPLAYER_REF}/get_iplayer \
      -o /usr/local/bin/get_iplayer && chmod +x /usr/local/bin/get_iplayer
ENV GETIPLAYER_PROFILE=/data/profile IPLAYER_CACHE=/data/cache
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
