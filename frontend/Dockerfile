# 프론트 이미지: Nginx + 화면 파일(ADR 0011). 빌드 컨텍스트는 frontend/ 폴더다.
#   docker build -t stock-single-was-front frontend
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
RUN rm -rf /usr/share/nginx/html/*
COPY public/ /usr/share/nginx/html/
EXPOSE 80
