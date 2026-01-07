require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

Pod::Spec.new do |s|
  s.name           = 'AudioStream'
  s.version        = package['version']
  s.summary        = package['description'] || 'Audio streaming module for Expo'
  s.description    = package['description'] || 'Audio streaming module for Expo'
  s.license        = package['license'] || 'MIT'
  s.author         = package['author'] || 'Poppy'
  s.homepage       = package['homepage'] || 'https://github.com/poppy'
  s.platforms      = {
    :ios => '15.1'
  }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/poppy/audio-stream' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
end
