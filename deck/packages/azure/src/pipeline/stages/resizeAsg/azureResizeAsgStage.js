'use strict';

import { module } from 'angular';
import { AccountService, Registry, StageConstants } from '@spinnaker/core';

export const AZURE_PIPELINE_STAGES_RESIZEASG_AZURERESIZEASGSTAGE =
  'spinnaker.azure.pipeline.stage.azure.resizeAsgStage';
export const name = AZURE_PIPELINE_STAGES_RESIZEASG_AZURERESIZEASGSTAGE;

module(AZURE_PIPELINE_STAGES_RESIZEASG_AZURERESIZEASGSTAGE, [])
  .config(function () {
    Registry.pipeline.registerStage({
      provides: 'resizeServerGroup',
      alias: 'resizeAsg',
      cloudProvider: 'azure',
      templateUrl: require('./resizeAsgStage.html'),
      executionStepLabelUrl: require('./resizeAsgStepLabel.html'),
      validators: [
        { type: 'requiredField', fieldName: 'cluster' },
        { type: 'requiredField', fieldName: 'target' },
        { type: 'requiredField', fieldName: 'regions' },
        { type: 'requiredField', fieldName: 'credentials', fieldLabel: 'account' },
        { type: 'requiredField', fieldName: 'targetSize', fieldLabel: 'target size' },
      ],
    });
  })
  .controller('azureResizeAsgStageCtrl', [
    '$scope',
    function ($scope) {
      const stage = $scope.stage;

      $scope.state = {
        accounts: false,
        regionsLoaded: false,
      };

      AccountService.listAccounts('azure').then(function (accounts) {
        $scope.accounts = accounts;
        $scope.state.accounts = true;
      });

      $scope.targets = StageConstants.TARGET_LIST;

      stage.regions = stage.regions || [];
      stage.cloudProvider = 'azure';

      // Bypass health checks for Azure resize operations
      // Azure handles health checking internally via waitForScaleSetHealthy
      if (stage.isNew) {
        stage.interestingHealthProviderNames = [];
      }

      if (!stage.credentials && $scope.application.defaultCredentials.azure) {
        stage.credentials = $scope.application.defaultCredentials.azure;
      }
      if (!stage.regions.length && $scope.application.defaultRegions.azure) {
        stage.regions.push($scope.application.defaultRegions.azure);
      }

      if (!stage.target) {
        stage.target = $scope.targets[0].val;
      }

      $scope.$watch('stage.targetSize', function (newVal) {
        if (newVal !== undefined && newVal !== null) {
          stage.capacity = {
            min: newVal,
            max: newVal,
            desired: newVal,
          };
        }
      });
    },
  ]);
