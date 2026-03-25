'use strict';

import { module } from 'angular';
import _ from 'lodash';

import { InfrastructureCaches, LOAD_BALANCER_READ_SERVICE, ModalWizard, NetworkReader } from '@spinnaker/core';

import Utility from '../../../../utility';

export const AZURE_SERVERGROUP_CONFIGURE_WIZARD_LOADBALANCERS_SERVERGROUPLOADBALANCERS_CONTROLLER =
  'spinnaker.azure.serverGroup.configure.loadBalancer.controller';
export const name = AZURE_SERVERGROUP_CONFIGURE_WIZARD_LOADBALANCERS_SERVERGROUPLOADBALANCERS_CONTROLLER; // for backwards compatibility
module(AZURE_SERVERGROUP_CONFIGURE_WIZARD_LOADBALANCERS_SERVERGROUPLOADBALANCERS_CONTROLLER, [
  LOAD_BALANCER_READ_SERVICE,
]).controller('azureServerGroupLoadBalancersCtrl', [
  '$scope',
  'loadBalancerReader',
  function ($scope, loadBalancerReader) {
    ModalWizard.markClean('load-balancers');

    this.allLoadBalancersLoaded = false;
    this.refreshing = false;
    this.availableResourceGroups = [];

    function getDefaultResourceGroup() {
      return $scope.command.application + '-' + $scope.command.region.replace(/\s/g, '').toLowerCase();
    }

    this.updateAvailableResourceGroups = function () {
      const allLBs = $scope.command.backingData.loadBalancers;
      const defaultRG = getDefaultResourceGroup();
      const rgsFromLBs = _.chain(allLBs)
        .filter(function (lb) {
          return lb.account === $scope.command.credentials && lb.region === $scope.command.region && lb.resourceGroup;
        })
        .map('resourceGroup')
        .uniq()
        .value()
        .sort();
      // Ensure the default resource group is always in the list
      if (!rgsFromLBs.includes(defaultRG)) {
        rgsFromLBs.unshift(defaultRG);
      }
      this.availableResourceGroups = rgsFromLBs;
    };

    this.filterLoadBalancersByResourceGroup = function () {
      const allLBs = $scope.command.backingData.loadBalancers;
      const resourceGroup = $scope.command.loadBalancerResourceGroup;
      const filtered = _.filter(allLBs, function (lb) {
        return (
          lb.account === $scope.command.credentials &&
          lb.region === $scope.command.region &&
          (!resourceGroup || !lb.resourceGroup || lb.resourceGroup === resourceGroup)
        );
      });
      $scope.command.loadBalancers = _.chain(filtered).map('name').uniq().value().sort();
    };

    function loadVnetSubnets(item, type) {
      loadBalancerReader
        .getLoadBalancerDetails('azure', $scope.command.credentials, $scope.command.region, item)
        .then(function (LBs) {
          if (!LBs || LBs.length === 0) {
            // load the subnet and vnets without a load balancer
            const attachedVnet = $scope.command.selectedVnet;
            const attachedSubnet = $scope.command.selectedSubnet;
            $scope.command.selectedVnetSubnets = [];
            $scope.command.allVnets = [];
            NetworkReader.listNetworks().then(function (vnets) {
              if (vnets.azure) {
                vnets.azure.forEach((selectedVnet) => {
                  if (
                    selectedVnet.account === $scope.command.credentials &&
                    selectedVnet.region === $scope.command.region
                  ) {
                    $scope.command.allVnets.push(selectedVnet);

                    selectedVnet.subnets.map(function (subnet) {
                      let addSubnet = true;
                      if (subnet.devices) {
                        subnet.devices.map(function (device) {
                          // only add subnets that are not assigned to an ApplicationGateway
                          if (device && device.type === 'applicationGateways') {
                            addSubnet = false;
                          }
                        });
                      }
                      if (addSubnet) {
                        $scope.command.selectedVnetSubnets.push(subnet.name);
                        if (subnet.name === attachedSubnet) {
                          $scope.command.selectedSubnet = attachedSubnet;
                        }
                      }
                    });
                  }
                });
              }
            });
          } else if (LBs && LBs.length === 1) {
            const selectedLoadBalancer = LBs[0];
            const attachedVnet = $scope.command.selectedVnet;
            $scope.command.selectedVnet = null;
            $scope.command.selectedVnetSubnets = [];
            $scope.command.allVnets = [];
            NetworkReader.listNetworks().then(function (vnets) {
              if (vnets.azure) {
                vnets.azure.forEach((selectedVnet) => {
                  if (
                    selectedVnet.account === $scope.command.credentials &&
                    selectedVnet.region === $scope.command.region
                  ) {
                    $scope.command.allVnets.push(selectedVnet);
                  }
                  if (
                    selectedVnet.account === $scope.command.credentials &&
                    selectedVnet.region === $scope.command.region &&
                    ((type === 'Azure Application Gateway' && selectedVnet.name == selectedLoadBalancer.vnet) ||
                      (type === 'Azure Load Balancer' && selectedVnet.name === attachedVnet.name))
                  ) {
                    $scope.command.selectedVnet = selectedVnet;
                    selectedVnet.subnets.map(function (subnet) {
                      let addSubnet = true;
                      if (subnet.devices) {
                        subnet.devices.map(function (device) {
                          // only add subnets that are not assigned to an ApplicationGateway
                          if (device && device.type === 'applicationGateways') {
                            addSubnet = false;
                          }
                        });
                      }
                      if (addSubnet) {
                        $scope.command.selectedVnetSubnets.push(subnet.name);
                      }
                    });
                  }
                });
              }
            });
          }
        });
    }

    if ($scope.command.credentials && $scope.command.region) {
      $scope.command.viewState.networkSettingsConfigured = true;
      $scope.command.selectedVnetSubnets = [];
      if ($scope.command.loadBalancerName !== null && typeof $scope.command.loadBalancerName !== 'undefined') {
        $scope.useLoadBalancer = true;
      }
      if ($scope.useLoadBalancer) {
        if (!$scope.command.loadBalancerResourceGroup) {
          $scope.command.loadBalancerResourceGroup = getDefaultResourceGroup();
        }
        this.availableResourceGroups = [$scope.command.loadBalancerResourceGroup];
        this.filterLoadBalancersByResourceGroup();
      }
      loadVnetSubnets($scope.command.loadBalancerName, $scope.command.loadBalancerType);
    }

    this.showAllLoadBalancers = function () {
      this.refreshing = true;
      loadBalancerReader.listLoadBalancers('azure').then((summaries) => {
        // Flatten the hierarchical response into a flat array matching the
        // shape returned by loadLoadBalancers (account, region, name, etc.)
        const flattened = [];
        summaries.forEach(function (summary) {
          (summary.accounts || []).forEach(function (acct) {
            (acct.regions || []).forEach(function (region) {
              (region.loadBalancers || []).forEach(function (lb) {
                flattened.push(lb);
              });
            });
          });
        });
        $scope.command.backingData.loadBalancers = flattened;
        this.updateAvailableResourceGroups();
        this.filterLoadBalancersByResourceGroup();
        this.allLoadBalancersLoaded = true;
        this.refreshing = false;
      });
    };

    this.resourceGroupChanged = function () {
      $scope.command.loadBalancerName = null;
      $scope.command.loadBalancerType = null;
      $scope.command.backendPoolName = null;
      this.filterLoadBalancersByResourceGroup();
    };

    this.loadBalancerChanged = function (item) {
      $scope.command.viewState.networkSettingsConfigured = true;
      ModalWizard.markComplete('load-balancers');

      if (item === null) {
        $scope.command.loadBalancerName = null;
        $scope.command.loadBalancerType = null;
        $scope.command.backendPoolName = null;

        if ($scope.useLoadBalancer) {
          // Checkbox was just checked — set default resource group and filter
          $scope.command.loadBalancerResourceGroup = getDefaultResourceGroup();
          this.availableResourceGroups = [$scope.command.loadBalancerResourceGroup];
          this.allLoadBalancersLoaded = false;
          this.filterLoadBalancersByResourceGroup();
        } else {
          // Checkbox was unchecked — clear everything
          $scope.command.loadBalancerResourceGroup = null;
          this.availableResourceGroups = [];
        }

        $scope.command.selectedVnetSubnets = [];
        InfrastructureCaches.clearCache('networks');
        loadVnetSubnets(null, null);
        return;
      }

      // A load balancer was selected from the dropdown
      const loadBalancers = $scope.command.backingData.loadBalancers;
      let loadBalancerType = null;

      if (loadBalancers) {
        const loadBalancerToFind = loadBalancers.find((lb) => lb.name === item);
        if (loadBalancerToFind) {
          loadBalancerType = Utility.getLoadBalancerType(loadBalancerToFind.loadBalancerType).type;
        }
      }

      $scope.command.backendPoolName = null;
      $scope.command.selectedVnetSubnets = [];
      $scope.command.loadBalancerType = loadBalancerType;
      InfrastructureCaches.clearCache('networks');
      loadVnetSubnets(item, loadBalancerType);
    };
  },
]);
