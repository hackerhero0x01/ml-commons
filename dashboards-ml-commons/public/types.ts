/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

import { NavigationPublicPluginStart } from '../../../src/plugins/navigation/public';

export interface MlCommonsPluginPluginSetup {
  getGreeting: () => string;
}
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface MlCommonsPluginPluginStart { }

export interface AppPluginStartDependencies {
  navigation: NavigationPublicPluginStart;
}
