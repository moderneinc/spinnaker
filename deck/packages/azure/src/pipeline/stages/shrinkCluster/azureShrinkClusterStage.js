'use strict';

import { module } from 'angular';

import { AccountService, Registry } from '@spinnaker/core';

export const AZURE_PIPELINE_STAGES_SHRINKCLUSTER_AZURESHRINKCLUSTERSTAGE =
  'spinnaker.azure.pipeline.stage.azure.shrinkClusterStage';
export const name = AZURE_PIPELINE_STAGES_SHRINKCLUSTER_AZURESHRINKCLUSTERSTAGE; // for backwards compatibility
module(AZURE_PIPELINE_STAGES_SHRINKCLUSTER_AZURESHRINKCLUSTERSTAGE, [])
  .config(function () {
    Registry.pipeline.registerStage({
      provides: 'shrinkCluster',
      cloudProvider: 'azure',
      templateUrl: require('./shrinkClusterStage.html'),
      accountExtractor: (stage) => [stage.context.credentials],
      configAccountExtractor: (stage) => [stage.credentials],
      validators: [
        { type: 'requiredField', fieldName: 'cluster' },
        { type: 'requiredField', fieldName: 'shrinkToSize', fieldLabel: 'shrink to [X] Server Groups' },
        { type: 'requiredField', fieldName: 'regions' },
        { type: 'requiredField', fieldName: 'credentials', fieldLabel: 'account' },
      ],
    });
  })
  .controller('azureShrinkClusterStageCtrl', [
    '$scope',
    function ($scope) {
      const ctrl = this;

      const stage = $scope.stage;

      $scope.state = {
        accounts: false,
        regionsLoaded: false,
      };

      AccountService.listAccounts('azure').then(function (accounts) {
        $scope.accounts = accounts;
        $scope.state.accounts = true;
      });

      stage.regions = stage.regions || [];
      stage.cloudProvider = 'azure';

      if (!stage.credentials && $scope.application.defaultCredentials.azure) {
        stage.credentials = $scope.application.defaultCredentials.azure;
      }
      if (!stage.regions.length && $scope.application.defaultRegions.azure) {
        stage.regions.push($scope.application.defaultRegions.azure);
      }

      if (stage.shrinkToSize === undefined) {
        stage.shrinkToSize = 1;
      }

      if (stage.allowDeleteActive === undefined) {
        stage.allowDeleteActive = false;
      }

      ctrl.pluralize = function (str, val) {
        if (val === 1) {
          return str;
        }
        return str + 's';
      };

      if (stage.retainLargerOverNewer === undefined) {
        stage.retainLargerOverNewer = 'false';
      }
      stage.retainLargerOverNewer = stage.retainLargerOverNewer.toString();
    },
  ]);
