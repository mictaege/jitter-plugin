package com.github.mictaege.jitter.plugin

import groovy.transform.Canonical

@Canonical
class CriticalTermsCfg {
    def patterns = []
    def includes = ['generated-sources/spoon/**', 'resources/**']
    def excludes = ['**/*.jpg', '**/*.jpeg', '**/*.png', '**/*.gif', '**/*.tif', '**/*.ico', '**/*.zip', '**/*.tar', '**/*.gz', '**/*.jar', '**/*.war', '**/*.ear']
    int sizeLimitKb = 250


}
