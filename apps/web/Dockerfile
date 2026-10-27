# Imagen inmutable del frontend: las páginas viajan dentro de la imagen (no dependen de un bind mount del host,
# que en Docker Desktop puede perderse tras suspender el equipo → 500 "No such device").
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY *.html /usr/share/nginx/html/
COPY assets /usr/share/nginx/html/assets
