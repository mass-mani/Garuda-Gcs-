#include "UAVLogViewerServer.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMimeDatabase>
#include <QNetworkReply>
#include <QPointer>
#include <QTcpSocket>
#include <QTimer>

UAVLogViewerServer::UAVLogViewerServer(QObject *parent) : QObject(parent)
{
    connect(&_server, &QTcpServer::newConnection, this, [this]() {
        while (_server.hasPendingConnections()) {
            auto *socket = _server.nextPendingConnection();
            connect(socket, &QTcpSocket::disconnected, socket, &QObject::deleteLater);
            QTimer::singleShot(30000, socket, [socket]() { socket->disconnectFromHost(); });
            connect(socket, &QTcpSocket::readyRead, this, [this, socket]() {
                if (socket->property("handled").toBool()) return;
                QByteArray data = socket->property("header").toByteArray() + socket->readAll();
                if (data.size() > 16384) {
                    socket->setProperty("handled", true);
                    respond(socket, 413, "text/plain", "Request too large");
                } else if (data.contains("\r\n\r\n")) {
                    socket->setProperty("handled", true);
                    handleRequest(socket, data);
                } else {
                    socket->setProperty("header", data);
                }
            });
        }
    });
}

void UAVLogViewerServer::start()
{
    if (_server.isListening()) return;
    const QStringList candidates = {
        QCoreApplication::applicationDirPath() + "/uavlogviewer",
        QCoreApplication::applicationDirPath() + "/../share/garuda/uavlogviewer",
        QStringLiteral(GARUDA_UAVLOGVIEWER_DIST)
    };
    for (const auto &path : candidates) {
        if (QFileInfo::exists(path + "/index.html")) {
            _root = QFileInfo(path).canonicalFilePath();
            break;
        }
    }
    if (_root.isEmpty()) {
        _error = tr("UAV Log Viewer assets are missing. Run the Build UAV Log Viewer task, then reopen this tab.");
    } else if (!_server.listen(QHostAddress::LocalHost, 0)) {
        _error = tr("Unable to start UAV Log Viewer: %1").arg(_server.errorString());
    } else {
        _error.clear();
        _url = QUrl(QStringLiteral("http://127.0.0.1:%1/").arg(_server.serverPort()));
    }
    emit statusChanged();
}

void UAVLogViewerServer::respond(QTcpSocket *socket, int code, const QByteArray &type,
                                 const QByteArray &body, bool head)
{
    if (!socket || socket->state() != QAbstractSocket::ConnectedState) return;
    QByteArray header = "HTTP/1.1 " + QByteArray::number(code) + (code == 200 ? " OK\r\n" : " Error\r\n");
    header += "Content-Type: " + type + "\r\nContent-Length: " + QByteArray::number(body.size());
    header += "\r\nCache-Control: no-cache\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n";
    socket->write(header);
    if (!head) socket->write(body);
    socket->disconnectFromHost();
}

void UAVLogViewerServer::handleRequest(QTcpSocket *socket, const QByteArray &header)
{
    const auto lines = header.split('\n');
    const auto request = lines.value(0).trimmed().split(' ');
    const bool head = request.value(0) == "HEAD";
    if (request.size() != 3 || (request[0] != "GET" && !head)) {
        respond(socket, 405, "text/plain", "Only GET and HEAD are supported");
        return;
    }
    QByteArray host;
    for (const auto &line : lines) {
        if (line.toLower().startsWith("host:")) host = line.mid(5).trimmed();
    }
    if (host != "127.0.0.1:" + QByteArray::number(_server.serverPort())) {
        respond(socket, 403, "text/plain", "Invalid host");
        return;
    }
    const QUrl requestUrl = QUrl::fromEncoded(request[1]);
    const QString path = requestUrl.path(QUrl::FullyDecoded);
    if (!path.startsWith('/') || path.contains("..") || path.contains('\\') || path.contains(QChar::Null)) {
        respond(socket, 403, "text/plain", "Invalid path");
        return;
    }
    if (path.startsWith("/quantized/")) {
        // Fixed upstream terrain endpoint. Never forwards local log data.
        QUrl terrain(QStringLiteral("https://plot.ardupilot.org"));
        terrain.setPath(path);
        QNetworkRequest networkRequest(terrain);
        networkRequest.setTransferTimeout(20000);
        auto *reply = _network.get(networkRequest);
        QPointer<QTcpSocket> client(socket);
        connect(reply, &QNetworkReply::finished, this, [reply, client, head]() {
            if (client) {
                respond(client, reply->error() == QNetworkReply::NoError ? 200 : 502,
                        reply->header(QNetworkRequest::ContentTypeHeader).toByteArray(), reply->readAll(), head);
            }
            reply->deleteLater();
        });
        return;
    }
    const QString filePath = QFileInfo(_root + (path == "/" ? "/index.html" : path)).canonicalFilePath();
    if (!filePath.startsWith(_root + '/', Qt::CaseInsensitive)) {
        respond(socket, 404, "text/plain", "Not found");
        return;
    }
    QFile file(filePath);
    if (!file.open(QIODevice::ReadOnly)) {
        respond(socket, 404, "text/plain", "Not found");
        return;
    }
    const QByteArray mime = filePath.endsWith(".js") ? QByteArray("application/javascript")
        : QMimeDatabase().mimeTypeForFile(filePath).name().toUtf8();
    respond(socket, 200, mime, file.readAll(), head);
}
