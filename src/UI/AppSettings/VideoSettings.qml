/****************************************************************************
 *
 * (c) 2009-2020 QGROUNDCONTROL PROJECT <http://www.qgroundcontrol.org>
 *
 * QGroundControl is licensed according to the terms in the file
 * COPYING.md in the root of the source code directory.
 *
 ****************************************************************************/


import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import QGroundControl
import QGroundControl.FactSystem
import QGroundControl.FactControls
import QGroundControl.Controls
import QGroundControl.ScreenTools

SettingsPage {
    property var _videoSettings: QGroundControl.settingsManager.videoSettings

    Repeater {
        model: QGroundControl.videoManager.videoSources
        delegate: VideoSourceSettings {
            required property var modelData
            required property int index
            Layout.fillWidth: true
            sourceSettings: modelData
            sourceNumber: index + 1
        }
    }
    SettingsGroupLayout {
        Layout.fillWidth: true
        heading: qsTr("Shared Decoder and Storage")
        LabelledFactComboBox {
            Layout.fillWidth: true
            label: qsTr("Video decode priority (restart required)")
            fact: _videoSettings.forceVideoDecoder
            indexModel: false
            visible: fact.visible
        }
        FactCheckBoxSlider {
            Layout.fillWidth: true
            text: qsTr("Auto-Delete Saved Recordings")
            fact: _videoSettings.enableStorageLimit
        }
        LabelledFactTextField {
            Layout.fillWidth: true
            label: qsTr("Max Storage Usage")
            fact: _videoSettings.maxVideoSize
            enabled: _videoSettings.enableStorageLimit.rawValue
        }
    }
}
