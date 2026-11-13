/****************************************************************************
 *
 * (c) 2009-2020 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/

import QtQuick
import QGroundControl

Item {
    id: videoBackground

    Loader {
        anchors.fill: parent
        source: QGroundControl.videoManager.qtmultimediaEnabled
                ? "qrc:/qml/QGroundControl/FlightDisplay/FlightDisplayViewQtMultimedia.qml"
                : "qrc:/qml/QGroundControl/FlightDisplay/FlightDisplayViewGStreamer.qml"
    }
}
